package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 阿里云百炼(DashScope)实时语音合成后端：走 {@code /api-ws/v1/realtime} 双向 WebSocket，
 * 用 qwen3-tts 实时模型把文字朗读成语音。
 *
 * <p>与 {@link OpenAiCompatibleTts} 的 REST {@code /v1/audio/speech} 不同，这里用实时模型的
 * {@code response.audio.delta} 流式拿 16-bit PCM，自行封装成 WAV 字节返回——
 * 因为 {@link WavCodec} 只认 PCM/WAV，而百炼的 REST 音频生成接口（CosyVoice 等）与本模组
 * 预期的 OpenAI 兼容路径不一致、且返回格式不保证是 PCM-WAV。
 *
 * <p>实时音频输出为 24kHz/16-bit/单声道 PCM（见 {@link #SAMPLE_RATE}），封装成同规格 WAV
 * 后落在 {@link WavCodec} 支持的 8k–48k 区间内，无需重采样。
 */
public final class DashScopeTts implements TtsBackend {

    /** 后端标识，与 voice.json 的 entry.backend 对应。 */
    public static final String BACKEND = "dashscope";
    /**
     * 专用实时语音合成模型。
     *
     * <p>不要用 {@code qwen-audio-3.0-realtime-flash} 这类实时对话模型来做 TTS：
     * 它会回答输入文字，而不是逐字朗读已经生成的回复。
     */
    public static final String DEFAULT_MODEL = "qwen3-tts-flash-realtime";
    /** realtime 音频输出采样率（qwen3-tts 实时为 24kHz PCM）。 */
    private static final int SAMPLE_RATE = 24_000;
    private static final String WS_BASE = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String url;     // 预留；realtime 用固定 WS 地址，baseUrl 不参与
    private final String apiKey;
    private final String model;
    private final String voice;

    public DashScopeTts(String baseUrl, String apiKey, String model, String voice) {
        this.url = baseUrl == null ? "" : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
        // 配置里 voice 可能是 "model:voice" 形式，realtime 只需音色名本身
        String v = voice == null ? "" : voice;
        this.voice = v.contains(":") ? v.substring(v.indexOf(':') + 1) : v;
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("TTS text is empty"));
        }
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        try {
            CLIENT.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(webSocketUri(model), new WsListener(text, result))
                    // 握手失败（key 无效/DNS/拒绝升级）走这里：不能让调用方永远挂起
                    .whenComplete((socket, ex) -> {
                        if (ex != null) {
                            result.completeExceptionally(ex);
                        }
                    });
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    @Override
    public String describe() {
        return "dashscope-realtime-tts(" + model + ", voice=" + voice + ")";
    }

    static List<String> buildRequestMessages(String text, String voice) {
        JsonObject update = new JsonObject();
        update.addProperty("type", "session.update");
        JsonObject session = new JsonObject();
        session.addProperty("mode", "server_commit");
        if (voice != null && !voice.isEmpty()) {
            session.addProperty("voice", voice);
        }
        session.addProperty("response_format", "pcm");
        session.addProperty("sample_rate", SAMPLE_RATE);
        update.add("session", session);

        JsonObject append = new JsonObject();
        append.addProperty("type", "input_text_buffer.append");
        append.addProperty("text", text);

        JsonObject commit = new JsonObject();
        commit.addProperty("type", "input_text_buffer.commit");
        return List.of(update.toString(), append.toString(), commit.toString());
    }

    static URI webSocketUri(String model) {
        String encoded = URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(WS_BASE + encoded);
    }

    private final class WsListener implements WebSocket.Listener {

        private final String text;
        private final CompletableFuture<byte[]> result;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile WebSocket ws;
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        private final StringBuilder fragments = new StringBuilder();

        WsListener(String text, CompletableFuture<byte[]> result) {
            this.text = text;
            this.result = result;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            ws = webSocket;
            sendSessionUpdate();
            webSocket.request(1);
        }

        private void sendSessionUpdate() {
            for (String message : buildRequestMessages(text, voice)) {
                ws.sendText(message, true);
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                try {
                    JsonObject e = JsonParser.parseString(fragments.toString()).getAsJsonObject();
                    String type = e.has("type") ? e.get("type").getAsString() : "";
                    if ("response.audio.delta".equals(type) && e.has("delta")) {
                        pcm.write(Base64.getDecoder().decode(e.get("delta").getAsString()));
                    } else if ("response.done".equals(type)) {
                        complete();
                    } else if ("error".equals(type)) {
                        String msg = "DashScope TTS error";
                        if (e.has("error")) {
                            JsonObject err = e.getAsJsonObject("error");
                            if (err.has("message")) {
                                msg = err.get("message").getAsString();
                            }
                        }
                        fail(new IllegalStateException(msg));
                    }
                } catch (Exception ex) {
                    fail(ex);
                } finally {
                    fragments.setLength(0);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!finished.get()) {
                fail(new IllegalStateException("DashScope TTS connection closed: " + reason));
            }
            return CompletableFuture.completedFuture(null);
        }

        private void complete() {
            if (finished.compareAndSet(false, true)) {
                try {
                    result.complete(toWav(pcm.toByteArray()));
                } catch (Exception ex) {
                    result.completeExceptionally(ex);
                } finally {
                    closeQuietly();
                }
            }
        }

        private void fail(Throwable t) {
            if (finished.compareAndSet(false, true)) {
                result.completeExceptionally(t);
                closeQuietly();
            }
        }

        private void closeQuietly() {
            try {
                if (ws != null) ws.sendClose(1000, "done");
            } catch (Exception ignored) {
                // 已关闭则忽略
            }
        }

    }

    /** 把 16-bit LE 单声道 PCM 包成 RIFF/WAVE，采样率 {@link #SAMPLE_RATE}。 */
    static byte[] toWav(byte[] pcmBytes) {
        if (pcmBytes == null || pcmBytes.length == 0 || (pcmBytes.length & 1) != 0) {
            throw new IllegalArgumentException("DashScope returned empty or odd-length PCM");
        }
        int dataLen = pcmBytes.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(out)) {
            d.writeBytes("RIFF");
            d.writeInt(Integer.reverseBytes(36 + dataLen));
            d.writeBytes("WAVE");
            d.writeBytes("fmt ");
            d.writeInt(Integer.reverseBytes(16));
            d.writeShort(Short.reverseBytes((short) 1));
            d.writeShort(Short.reverseBytes((short) 1));
            d.writeInt(Integer.reverseBytes(SAMPLE_RATE));
            d.writeInt(Integer.reverseBytes(SAMPLE_RATE * 2));
            d.writeShort(Short.reverseBytes((short) 2));
            d.writeShort(Short.reverseBytes((short) 16));
            d.writeBytes("data");
            d.writeInt(Integer.reverseBytes(dataLen));
            d.write(pcmBytes);
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
        return out.toByteArray();
    }
}
