package com.dwinovo.numen.client.stt;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 * 阿里云百炼(DashScope)实时语音识别:{@code /api-ws/v1/inference} 双向 WebSocket。
 *
 * <p>控制指令走文本帧({@code run-task} / {@code finish-task}),音频走二进制帧,识别结果以
 * {@code result-generated} 事件推回来。{@code sentence_end} 区分中间结果和定稿的句子——
 * 这就是 {@link SttListener#onPartial} 与 {@link SttListener#onFinal} 的分界。
 *
 * <h2>模型选的是专用 ASR,不是对话模型</h2>
 * 百炼的 {@code qwen-audio-3.0-realtime-flash} 是<b>实时对话</b>模型,它会理解你说的话并回答;
 * 拿它的输入转写侧当语音识别用属于借道。这里用官方当前推荐的专用流式识别模型
 * {@link #DEFAULT_MODEL},换 {@code fun-asr-realtime} 系列也是同一套协议,改 model 即可。
 *
 * <p>音频不用转:{@link SttAudio#FORMAT} 就是 16kHz/16-bit/单声道裸 PCM,首帧里的格式与采样率
 * 都从那个常量取。
 */
public final class DashScopeStt implements SttBackend {

    /** 实时识别的固定入口。带业务空间专属域名的账号可在设置里覆盖。 */
    public static final String DEFAULT_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    /** 官方当前推荐的流式识别模型。 */
    public static final String DEFAULT_MODEL = "qwen-audio-3.0-asr-flash-streaming";

    /** 等 {@code task-started} 的上限——没这一步不许发音频。 */
    private static final int READY_WAIT_SEC = 15;
    /** 发完 {@code finish-task} 等 {@code task-finished} 的上限。 */
    private static final int FINAL_WAIT_SEC = 10;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            // 明文 http 下 JDK 默认发 h2c 升级头,自建服务端(Uvicorn 等)刷警告;钉死 1.1。
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String url;
    private final String apiKey;
    private final String model;

    public DashScopeStt(String baseUrl, String apiKey, String model) {
        this.url = baseUrl == null || baseUrl.isBlank() ? DEFAULT_URL : baseUrl.strip();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
    }

    /** {@code run-task}:开任务并交待音频格式。格式三项从 {@link SttAudio#FORMAT} 取。 */
    static String runTask(String taskId, String model) {
        JsonObject header = new JsonObject();
        header.addProperty("action", "run-task");
        header.addProperty("task_id", taskId);
        header.addProperty("streaming", "duplex");

        JsonObject parameters = new JsonObject();
        parameters.addProperty("format", "pcm");
        parameters.addProperty("sample_rate", (int) SttAudio.FORMAT.getSampleRate());

        JsonObject payload = new JsonObject();
        payload.addProperty("task_group", "audio");
        payload.addProperty("task", "asr");
        payload.addProperty("function", "recognition");
        payload.addProperty("model", model);
        payload.add("parameters", parameters);
        payload.add("input", new JsonObject());

        JsonObject root = new JsonObject();
        root.add("header", header);
        root.add("payload", payload);
        return root.toString();
    }

    /** {@code finish-task}:告诉服务端没有音频了,它把剩下的结果吐完再回 {@code task-finished}。 */
    static String finishTask(String taskId) {
        JsonObject header = new JsonObject();
        header.addProperty("action", "finish-task");
        header.addProperty("task_id", taskId);
        header.addProperty("streaming", "duplex");

        JsonObject payload = new JsonObject();
        payload.add("input", new JsonObject());

        JsonObject root = new JsonObject();
        root.add("header", header);
        root.add("payload", payload);
        return root.toString();
    }

    /** 服务端事件里我们认的那几样。 */
    record Event(String name, String text, boolean sentenceEnd, String error) {}

    /** 读一条服务端事件。读不懂当作"没这条",不当错——心跳等无关事件本来就该忽略。 */
    static Event readEvent(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject header = root.getAsJsonObject("header");
            String name = header.has("event") ? header.get("event").getAsString() : "";
            if ("task-failed".equals(name)) {
                String code = header.has("error_code") ? header.get("error_code").getAsString() : "?";
                String message = header.has("error_message")
                        ? header.get("error_message").getAsString() : "";
                return new Event(name, null, false, code + (message.isBlank() ? "" : ": " + message));
            }
            if (!"result-generated".equals(name) || !root.has("payload")) {
                return new Event(name, null, false, null);
            }
            JsonObject output = root.getAsJsonObject("payload").getAsJsonObject("output");
            if (output == null || !output.has("sentence")) {
                return new Event(name, null, false, null);
            }
            JsonObject sentence = output.getAsJsonObject("sentence");
            // 心跳包也长这样,但没有文字——别让它把已有的转写覆盖成空
            String text = sentence.has("text") && !sentence.get("text").isJsonNull()
                    ? sentence.get("text").getAsString() : null;
            boolean end = sentence.has("sentence_end") && sentence.get("sentence_end").getAsBoolean();
            return new Event(name, text, end, null);
        } catch (RuntimeException e) {
            return new Event("", null, false, null);
        }
    }

    @Override
    public SttSession open(SttListener listener) {
        return new RealtimeSession(listener);
    }

    @Override
    public String describe() {
        return "dashscope-realtime-asr(" + url + ", model=" + model + ")";
    }

    /**
     * 一次流式会话。
     *
     * <h2>音频不能抢在 task-started 前面</h2>
     * 协议要求先 {@code run-task}、收到 {@code task-started} 才许发音频。所以发送链的<b>链头是
     * 一个空 future,由监听线程在收到 task-started 时兑现</b>——握手期间喂进来的 PCM 自然挂在
     * 那儿等着,不用另备缓冲区和"能发了没"的标志位。JDK 的 WebSocket 也不允许上一次 send 未完成
     * 就发下一帧,同一条链顺带把这个约束也满足了。
     */
    private final class RealtimeSession implements SttSession {

        private final SttListener listener;
        private final String taskId = UUID.randomUUID().toString();
        private final AtomicBoolean settled = new AtomicBoolean();
        private final StringBuilder fragments = new StringBuilder();
        /** 已定稿的句子。一次录音可能被切成好几句,这里按顺序攒起来。 */
        private final StringBuilder finalized = new StringBuilder();

        private final CompletableFuture<WebSocket> started = new CompletableFuture<>();
        private CompletableFuture<WebSocket> chain = started;
        private volatile WebSocket socket;

        RealtimeSession(SttListener listener) {
            this.listener = listener;
            started.orTimeout(READY_WAIT_SEC, TimeUnit.SECONDS);
            watch(chain);
            watch(CLIENT.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(url), new Events())
                    .thenCompose(ws -> ws.sendText(runTask(taskId, model), true)
                            .thenApply(sent -> ws)));
        }

        @Override
        public synchronized void feed(byte[] pcm) {
            if (settled.get()) {
                return;
            }
            chain = chain.thenCompose(ws -> ws.sendBinary(ByteBuffer.wrap(pcm), true).thenApply(x -> ws));
            watch(chain);
        }

        @Override
        public synchronized void finish() {
            if (settled.get()) {
                return;
            }
            chain = chain.thenCompose(ws -> ws.sendText(finishTask(taskId), true).thenApply(x -> ws));
            watch(chain);
            CompletableFuture.runAsync(this::settle,
                    CompletableFuture.delayedExecutor(FINAL_WAIT_SEC, TimeUnit.SECONDS));
        }

        @Override
        public synchronized void cancel() {
            settled.set(true);
            chain.thenAccept(WebSocket::abort);
        }

        private void watch(CompletableFuture<WebSocket> link) {
            link.whenComplete((ws, error) -> {
                if (error != null) {
                    fail(error);
                }
            });
        }

        private void fail(Throwable error) {
            if (settled.compareAndSet(false, true)) {
                // 握手失败(key/模型不对)也走这里,不打就只剩界面上一闪而过的一句
                Constants.LOG.warn("[numen-stt] 百炼识别失败 model={}", model, error);
                listener.onError(error);
            }
        }

        private void settle() {
            if (settled.compareAndSet(false, true)) {
                listener.onFinal(finalized.toString().strip());
            }
        }

        private void handle(String json) {
            Event event = readEvent(json);
            if (event.error() != null) {
                fail(new IllegalStateException(event.error()));
                return;
            }
            switch (event.name()) {
                case "task-started" -> started.complete(socket);
                case "task-finished" -> settle();
                case "result-generated" -> {
                    if (event.text() == null) {
                        return;                      // 心跳:没带文字,什么都别动
                    }
                    if (event.sentenceEnd()) {
                        finalized.append(event.text());
                        listener.onPartial(finalized.toString());
                    } else {
                        // 定稿的部分 + 正在改的这句,合起来才是"现在听到了什么"
                        listener.onPartial(finalized + event.text());
                    }
                }
                default -> { /* session 级别的其它事件与识别无关 */ }
            }
        }

        /** 收事件。一条消息可能分几次到,{@code last} 为真才算完整一条。 */
        private final class Events implements WebSocket.Listener {

            @Override
            public void onOpen(WebSocket ws) {
                socket = ws;
                ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                fragments.append(data);
                if (last) {
                    String whole = fragments.toString();
                    fragments.setLength(0);
                    handle(whole);
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                // 已经收过 task-finished 的话 settled 为真,这就是正常收尾;否则算断线,
                // 不把半截转写当成功。
                fail(new IllegalStateException("连接被关闭 " + status
                        + (reason == null || reason.isBlank() ? "" : " " + reason)));
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                fail(error);
            }
        }
    }
}
