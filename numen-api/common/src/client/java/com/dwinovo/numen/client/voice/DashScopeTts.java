package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 阿里云百炼(DashScope)实时语音合成:{@code /api-ws/v1/realtime} 双向 WebSocket。
 *
 * <p>发三条事件、收一串音频分片:{@code session.update} 交代音色和输出格式,
 * {@code input_text_buffer.append} 递文本,{@code input_text_buffer.commit} 提交,
 * {@code session.finish} 说没有下文了。服务端把 PCM 分片放在 {@code response.audio.delta}
 * 的 base64 里推回来,{@code session.finished} / {@code response.done} 表示吐完了。
 *
 * <h2>用专用合成模型,别用对话模型</h2>
 * {@code qwen-audio-3.0-realtime-flash} 那类是<b>实时对话</b>模型:你把要念的文本递过去,
 * 它会把这句话当成提问来回答,而不是照着念。合成要用 {@link #DEFAULT_MODEL} 这样的专用模型。
 *
 * <h2>mode 跟着谁提交走</h2>
 * 我们手上是分好句的完整一句,所以用 {@code commit}——由客户端说什么时候提交。
 * {@code server_commit} 是让服务端自己判断断句时机的另一种用法,与手动 commit 不是一回事。
 *
 * <p>拿到的是裸 PCM,补上 WAV 头再交给 {@link WavCodec}。24kHz 落在它支持的 8k–48k 内,不重采样。
 */
public final class DashScopeTts implements TtsBackend {

    /** 实时合成的固定入口(model 以查询参数带上)。 */
    public static final String DEFAULT_BASE = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
    /** 专用实时合成模型。 */
    public static final String DEFAULT_MODEL = "qwen3-tts-flash-realtime";
    /** 音色留空时的缺省(官方内置音色之一)。 */
    public static final String DEFAULT_VOICE = "Cherry";
    /** 输出采样率。官方可选 8000/16000/24000/48000,24000 是默认档。 */
    static final int SAMPLE_RATE = 24_000;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            // 明文 http 下 JDK 默认发 h2c 升级头,自建服务端(Uvicorn 等)刷警告;钉死 1.1。
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String base;
    private final String apiKey;
    private final String model;
    private final String voice;

    public DashScopeTts(String baseUrl, String apiKey, String model, String voice) {
        this.base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE : baseUrl.strip();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        String v = voice == null ? "" : voice.strip();
        // 别家后端习惯把音色写成 "模型:音色";这里只要音色本身
        int colon = v.indexOf(':');
        String bare = colon < 0 ? v : v.substring(colon + 1).strip();
        this.voice = bare.isEmpty() ? DEFAULT_VOICE : bare;
    }

    static URI endpoint(String base, String model) {
        String sep = base.contains("?") ? "&" : "?";
        return URI.create(base + sep + "model="
                + URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    /**
     * 一次合成要发的三条事件,按顺序。
     *
     * <p>纯函数,可测——协议的形状锁在这里,改动一眼看得见。
     */
    static List<String> requestEvents(String voice, String text) {
        JsonObject session = new JsonObject();
        session.addProperty("voice", voice);
        session.addProperty("mode", "commit");
        session.addProperty("response_format", "pcm");
        session.addProperty("sample_rate", SAMPLE_RATE);

        JsonObject update = event("session.update");
        update.add("session", session);

        JsonObject append = event("input_text_buffer.append");
        append.addProperty("text", text);

        return List.of(update.toString(), append.toString(),
                event("input_text_buffer.commit").toString(),
                event("session.finish").toString());
    }

    private static JsonObject event(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("event_id", UUID.randomUUID().toString());
        o.addProperty("type", type);
        return o;
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("TTS 文本为空"));
        }
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        try {
            CLIENT.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(endpoint(base, model), new Events(text, result))
                    .whenComplete((ws, error) -> {
                        if (error != null) {
                            result.completeExceptionally(error);   // 握手失败:key 错/DNS/拒绝升级
                        }
                    });
        } catch (RuntimeException e) {
            result.completeExceptionally(e);   // 坏配置走异步失败通道,绝不同步炸
        }
        return result.orTimeout(VoiceHttp.REQUEST_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public String describe() {
        return "dashscope-realtime-tts(" + model + ", voice=" + voice + ")";
    }

    /** 一次合成的收发。 */
    private final class Events implements WebSocket.Listener {

        private final String text;
        private final CompletableFuture<byte[]> result;
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        private final StringBuilder fragments = new StringBuilder();
        private final AtomicBoolean settled = new AtomicBoolean();

        Events(String text, CompletableFuture<byte[]> result) {
            this.text = text;
            this.result = result;
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
            // JDK 不允许上一条 sendText 未完成就发下一条,串起来依次发
            CompletableFuture<WebSocket> chain = CompletableFuture.completedFuture(ws);
            for (String message : requestEvents(voice, text)) {
                chain = chain.thenCompose(s -> s.sendText(message, true).thenApply(x -> s));
            }
            chain.whenComplete((s, error) -> {
                if (error != null) {
                    fail(error);
                }
            });
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                String whole = fragments.toString();
                fragments.setLength(0);
                handle(whole, ws);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            // 收全了的话 settled 为真,这就是正常收尾;否则断在半路,不能把半段音频当成一句话
            fail(new IllegalStateException("连接被关闭 " + status
                    + (reason == null || reason.isBlank() ? "" : " " + reason)));
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            fail(error);
        }

        private void handle(String json, WebSocket ws) {
            JsonObject root;
            try {
                root = JsonParser.parseString(json).getAsJsonObject();
            } catch (RuntimeException e) {
                return;                       // 读不懂的事件忽略,别打断这一句
            }
            String type = root.has("type") ? root.get("type").getAsString() : "";
            switch (type) {
                case "response.audio.delta" -> {
                    if (root.has("delta") && !root.get("delta").isJsonNull()) {
                        byte[] chunk = Base64.getDecoder().decode(root.get("delta").getAsString());
                        pcm.writeBytes(chunk);
                    }
                }
                // 两个都表示音频吐完了,谁先到算谁
                case "response.done", "session.finished" -> {
                    finishWith(pcm.toByteArray());
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                }
                case "error" -> fail(new IllegalStateException(errorOf(root)));
                default -> { /* session.created / response.created 等与音频无关 */ }
            }
        }

        private String errorOf(JsonObject root) {
            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject error = root.getAsJsonObject("error");
                String code = error.has("code") ? error.get("code").getAsString() : "";
                String message = error.has("message") ? error.get("message").getAsString() : "";
                if (!message.isBlank()) {
                    return code.isBlank() ? message : code + ": " + message;
                }
            }
            return "百炼语音合成返回错误";
        }

        private void finishWith(byte[] raw) {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            try {
                result.complete(WavCodec.encodeMono16(raw, SAMPLE_RATE));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }

        private void fail(Throwable error) {
            if (settled.compareAndSet(false, true)) {
                result.completeExceptionally(error);
            }
        }
    }
}
