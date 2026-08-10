package com.dwinovo.numen.client.stt;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 豆包语音(火山引擎)大模型实时语音识别:{@code /api/v3/sauc/bigmodel} 双向 WebSocket。
 *
 * <p>这是第一个<b>真流式</b>的语音输入后端。{@link WhisperHttpStt} 那条路要等松开麦克风、
 * 打包整段 WAV、走完一次 HTTP 往返才出字;这里边采边发,说到一半服务端就把中间结果推回来,
 * 落在 {@link SttListener#onPartial} 上。
 *
 * <p>采集格式不用转:{@link SttAudio#FORMAT} 就是它要的 16kHz/16-bit/单声道裸 PCM,
 * 首帧里的采样率、位深、声道数都从那个常量取,不另写一份。
 *
 * <h2>凭据</h2>
 * 它要三样:appid、access token、resource id。配置里只有一个 key 框,所以 appid 和 token
 * 写成 {@code appid:access_token} 挤在一起——控制台上这两个本来就是成对发的。resource id
 * 走 model 那一栏(它真正的 {@code model_name} 恒等于 {@code bigmodel},没得选),预设里给
 * 按时长和按并发两档。
 */
public final class DoubaoStt implements SttBackend {

    /** 大模型流式识别的固定入口。 */
    public static final String DEFAULT_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel";
    /** 缺省资源档:按时长计费。另一档是 {@code volc.bigasr.sauc.concurrent}(按并发)。 */
    public static final String DEFAULT_RESOURCE_ID = "volc.bigasr.sauc.duration";
    /** 这个端点只有这一个模型名,不进 UI。 */
    private static final String MODEL_NAME = "bigmodel";

    /** 发完最后一包后等最终结果的上限:等不到就用手上已有的收尾,别让按钮卡在"转写中"。 */
    private static final int FINAL_WAIT_SEC = 10;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String url;
    private final String appId;
    private final String accessToken;
    private final String resourceId;

    public DoubaoStt(String baseUrl, String apiKey, String resourceId) {
        this.url = baseUrl == null || baseUrl.isBlank() ? DEFAULT_URL : baseUrl.strip();
        this.appId = part(apiKey, 0);
        this.accessToken = part(apiKey, 1);
        this.resourceId = resourceId == null || resourceId.isBlank()
                ? DEFAULT_RESOURCE_ID : resourceId.strip();
    }

    /** 拆 {@code appid:access_token};没有冒号就整串当 token,appid 空——上层据此报错。 */
    static String part(String apiKey, int index) {
        String s = apiKey == null ? "" : apiKey.strip();
        int colon = s.indexOf(':');
        if (colon < 0) {
            return index == 0 ? "" : s;
        }
        return (index == 0 ? s.substring(0, colon) : s.substring(colon + 1)).strip();
    }

    /** 首帧的会话参数。音频三项从 {@link SttAudio#FORMAT} 取,采集格式改了这里跟着改。 */
    static String sessionJson() {
        JsonObject audio = new JsonObject();
        audio.addProperty("format", "pcm");        // 裸 PCM,没有 WAV 头
        audio.addProperty("codec", "raw");
        audio.addProperty("rate", (int) SttAudio.FORMAT.getSampleRate());
        audio.addProperty("bits", SttAudio.FORMAT.getSampleSizeInBits());
        audio.addProperty("channel", SttAudio.FORMAT.getChannels());

        JsonObject request = new JsonObject();
        request.addProperty("model_name", MODEL_NAME);
        request.addProperty("enable_itn", true);     // "一百二十八" → "128"
        request.addProperty("enable_punc", true);    // 补标点
        request.addProperty("enable_ddc", true);     // 去掉"呃""那个"这类口癖
        request.addProperty("show_utterances", true);

        JsonObject root = new JsonObject();
        root.add("audio", audio);
        root.add("request", request);
        return root.toString();
    }

    @Override
    public SttSession open(SttListener listener) {
        return new StreamSession(listener);
    }

    @Override
    public String describe() {
        return "doubao-realtime(" + url + ", appid=" + appId + ", resource=" + resourceId + ")";
    }

    /**
     * 一次流式会话。
     *
     * <h2>为什么用一条 future 串起来发</h2>
     * JDK 的 {@link WebSocket} 不允许上一次 {@code sendBinary} 还没完成就发下一帧。所以所有
     * 发送挂在同一条 {@code chain} 上依次触发;而这条链的<b>链头就是连接本身</b>,于是握手还没
     * 完成时喂进来的 PCM 会自然排队等着,不用另外准备一个缓冲区和"连上了没"的标志。
     *
     * <h2>为什么攥着一块不发</h2>
     * 结束信号是"最后一包"这个标志位,得挂在一块真音频上。采集层只会先喂若干块、再说一声
     * 结束,不会预告哪块是最后一块——所以 {@link #feed} 总是发上一块、留住当前这块,
     * {@link #finish} 时把留住的那块带着标志发走。代价是固定晚 100ms(一块的时长)。
     */
    private final class StreamSession implements SttSession {

        private final SttListener listener;
        private final AtomicBoolean settled = new AtomicBoolean();   // 结果/错误只报一次
        private final ByteArrayOutputStream inbound = new ByteArrayOutputStream();

        private int seq = 1;
        private byte[] held;
        private volatile String text = "";
        private CompletableFuture<WebSocket> chain;

        StreamSession(SttListener listener) {
            this.listener = listener;
            chain = connect();
            watch(chain);
        }

        private CompletableFuture<WebSocket> connect() {
            if (appId.isBlank() || accessToken.isBlank()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "豆包语音的 API Key 要写成 appid:access_token 两段"));
            }
            return CLIENT.newWebSocketBuilder()
                    .header("X-Api-App-Key", appId)
                    .header("X-Api-Access-Key", accessToken)
                    .header("X-Api-Resource-Id", resourceId)
                    .header("X-Api-Connect-Id", UUID.randomUUID().toString())
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(url), new Frames())
                    .thenCompose(ws -> sendOn(ws, DoubaoFrames.fullClientRequest(next(), sessionJson())));
        }

        @Override
        public synchronized void feed(byte[] pcm) {
            if (settled.get()) {
                return;
            }
            if (held != null) {
                send(DoubaoFrames.audioRequest(next(), held, false));
            }
            held = pcm;
        }

        @Override
        public synchronized void finish() {
            if (settled.get()) {
                return;
            }
            byte[] tail = held == null ? new byte[0] : held;
            held = null;
            send(DoubaoFrames.audioRequest(next(), tail, true));
            // 正常情况服务端会回一个"最后一包";它没回也得收尾,不然按钮一直转
            CompletableFuture.runAsync(() -> settle(text),
                    CompletableFuture.delayedExecutor(FINAL_WAIT_SEC, TimeUnit.SECONDS));
        }

        @Override
        public synchronized void cancel() {
            settled.set(true);
            held = null;
            chain.thenAccept(WebSocket::abort);
        }

        private int next() {
            return seq++;
        }

        private void send(byte[] frame) {
            chain = chain.thenCompose(ws -> sendOn(ws, frame));
            watch(chain);
        }

        private CompletableFuture<WebSocket> sendOn(WebSocket ws, byte[] frame) {
            return ws.sendBinary(ByteBuffer.wrap(frame), true).thenApply(sent -> ws);
        }

        /** 链上任何一环断了都报一次错;{@code settled} 保证只报第一次。 */
        private void watch(CompletableFuture<WebSocket> link) {
            link.whenComplete((ws, error) -> {
                if (error != null) {
                    fail(error);
                }
            });
        }

        private void fail(Throwable error) {
            if (settled.compareAndSet(false, true)) {
                listener.onError(error);
            }
        }

        private void settle(String result) {
            if (settled.compareAndSet(false, true)) {
                listener.onFinal(result == null ? "" : result);
            }
        }

        private void handle(byte[] frame) {
            DoubaoFrames.Reply reply = DoubaoFrames.parse(frame);
            if (reply.error() != null) {
                fail(new IllegalStateException(reply.error()));
                return;
            }
            if (reply.text() != null) {
                text = reply.text();
            }
            if (reply.last()) {
                settle(text);
            } else if (reply.text() != null && !reply.text().isEmpty()) {
                listener.onPartial(reply.text());
            }
        }

        /** 收帧。一条消息可能分几次到,{@code last} 为真才算完整一帧。 */
        private final class Frames implements WebSocket.Listener {

            @Override
            public void onOpen(WebSocket ws) {
                ws.request(1);
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                byte[] part = new byte[data.remaining()];
                data.get(part);
                inbound.writeBytes(part);
                if (last) {
                    byte[] whole = inbound.toByteArray();
                    inbound.reset();
                    handle(whole);
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                // 已经收过最后一包的话 settled 为真,这里就是一次正常收尾;否则算断线失败,
                // 不把半截转写当成功。
                fail(new IllegalStateException("连接被关闭 " + status
                        + (reason == null || reason.isBlank() ? "" : " " + reason)));
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                Constants.LOG.warn("[numen-stt] 豆包实时识别连接出错", error);
                fail(error);
            }
        }
    }
}
