package com.dwinovo.numen.client.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 阿里云百炼(DashScope)实时语音合成后端：走 {@code /api-ws/v1/realtime} 双向 WebSocket
 * （OpenAI realtime 兼容协议），用 qwen-audio 实时模型把文字合成语音。
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
    /** 实时模型（同时具备 ASR + TTS 能力）；TTS 只用其合成侧。 */
    public static final String DEFAULT_MODEL = "qwen-audio-3.0-realtime-flash";
    /** realtime 音频输出采样率（qwen-audio 实时为 24kHz PCM）。 */
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
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        try {
            CLIENT.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(WS_BASE + model), new WsListener(text, result))
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

    private final class WsListener implements WebSocket.Listener {

        private final String text;
        private final CompletableFuture<byte[]> result;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile WebSocket ws;
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();

        WsListener(String text, CompletableFuture<byte[]> result) {
            this.text = text;
            this.result = result;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            ws = webSocket;
            WebSocket.Listener.super.onOpen(webSocket);
            sendSessionUpdate();
        }

        private void sendSessionUpdate() {
            JsonObject root = new JsonObject();
            root.addProperty("type", "session.update");
            JsonObject s = new JsonObject();
            JsonArray modalities = new JsonArray();
            modalities.add("audio");
            s.add("modalities", modalities);
            if (!voice.isEmpty()) {
                s.addProperty("voice", voice);
            }
            s.add("input_audio_transcription", JsonNull.INSTANCE);   // 合成不需要输入转写
            s.add("turn_detection", JsonNull.INSTANCE);
            root.add("session", s);
            ws.sendText(root.toString(), true);
            sendUserText();
        }

        private void sendUserText() {
            JsonObject item = new JsonObject();
            item.addProperty("type", "conversation.item.create");
            JsonObject it = new JsonObject();
            it.addProperty("type", "message");
            it.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject c = new JsonObject();
            c.addProperty("type", "input_text");
            c.addProperty("text", text);
            content.add(c);
            it.add("content", content);
            item.add("item", it);   // conversation.item.create 的 item 字段
            ws.sendText(item.toString(), true);
            JsonObject create = new JsonObject();
            create.addProperty("type", "response.create");
            ws.sendText(create.toString(), true);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                JsonObject e = JsonParser.parseString(data.toString()).getAsJsonObject();
                String type = e.has("type") ? e.get("type").getAsString() : "";
                if ("response.audio.delta".equals(type) && e.has("delta")) {
                    pcm.write(Base64.getDecoder().decode(e.get("delta").getAsString()));
                } else if ("response.done".equals(type) || "response.audio.done".equals(type)) {
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
            } catch (Exception ex) {   // IOException(pcm.write) 与 RuntimeException 都按失败处理
                fail(ex);
            }
            return null;   // 单帧小消息，无需分帧续传
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // 流意外中断时，尽量用已收到的音频完成（避免整句丢失）
            if (!finished.get()) {
                complete();
            }
            return null;
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
                ws.sendClose(1000, "done");
            } catch (Exception ignored) {
                // 已关闭则忽略
            }
        }

        /** 把 16-bit LE 单声道 PCM 包成 RIFF/WAVE，采样率 {@link #SAMPLE_RATE}。 */
        private byte[] toWav(byte[] pcmBytes) {
            int dataLen = pcmBytes.length;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DataOutputStream d = new DataOutputStream(out)) {
                d.writeBytes("RIFF");
                d.writeInt(Integer.reverseBytes(36 + dataLen));
                d.writeBytes("WAVE");
                d.writeBytes("fmt ");
                d.writeInt(Integer.reverseBytes(16));
                d.writeShort(Short.reverseBytes((short) 1));        // PCM
                d.writeShort(Short.reverseBytes((short) 1));        // 单声道
                d.writeInt(Integer.reverseBytes(SAMPLE_RATE));
                d.writeInt(Integer.reverseBytes(SAMPLE_RATE * 2));  // byte rate
                d.writeShort(Short.reverseBytes((short) 2));        // block align
                d.writeShort(Short.reverseBytes((short) 16));       // 16-bit
                d.writeBytes("data");
                d.writeInt(Integer.reverseBytes(dataLen));
                d.write(pcmBytes);
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
            return out.toByteArray();
        }
    }
}
