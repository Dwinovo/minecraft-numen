package com.dwinovo.numen.client.stt;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 阿里云百炼(DashScope)实时语音识别后端：走 {@code /api-ws/v1/realtime} 双向 WebSocket
 * （OpenAI realtime 兼容协议），用 qwen-audio 实时模型把麦克风 PCM 转成文字。
 *
 * <p>与 {@link WhisperHttpStt} 的"录完整段再批量上传"不同，这里 {@link #feed} 即发、
 * 流式回传增量，正好套进 {@link SttBackend} 为"流式 WebSocket 后端"预留的扩展点
 * （见 {@link SttBackend} 类注释）。
 *
 * <p>音频格式与 {@link SttAudio#FORMAT}（16k/16-bit/单声道）一致，直接喂，无需重采样。
 * 依赖 JDK 11+ 内置 {@link HttpClient}/{@link WebSocket}，零额外依赖。
 */
public final class DashScopeStt implements SttBackend {

    /** 后端标识，与 {@code numen_stt.json} 里的 provider.backend 对应。 */
    public static final String BACKEND = "dashscope";
    /** 实时模型（同时具备 ASR + TTS 能力）；STT 只用其识别侧。 */
    public static final String DEFAULT_MODEL = "qwen-audio-3.0-realtime-flash";
    private static final String WS_BASE = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiKey;
    private final String model;

    public DashScopeStt(String baseUrl, String apiKey, String model) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
    }

    @Override
    public SttSession open(SttListener listener) {
        return new RealtimeSession(listener);
    }

    @Override
    public String describe() {
        return "dashscope-realtime(" + model + ")";
    }

    private final class RealtimeSession implements SttSession {

        private final SttListener listener;
        private final AtomicBoolean done = new AtomicBoolean(false);
        private volatile WebSocket ws;
        /** 握手完成前到达的音频块与 finish 请求：连接就绪后按序补发，不丢。 */
        private final List<byte[]> pendingAudio = new ArrayList<>();
        private boolean finishRequested;

        RealtimeSession(SttListener listener) {
            this.listener = listener;
            connect();
        }

        private void connect() {
            try {
                CLIENT.newWebSocketBuilder()
                        .header("Authorization", "Bearer " + apiKey)
                        .connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(URI.create(WS_BASE + model), new WsListener())
                        .whenComplete((socket, ex) -> {
                            if (ex != null) {
                                if (done.compareAndSet(false, true)) {
                                    listener.onError(ex);
                                }
                                return;
                            }
                            ws = socket;
                            if (done.get()) {              // cancel() 先于握手完成：别发了，直接关
                                try {
                                    socket.sendClose(1000, "cancel");
                                } catch (Exception ignored) {
                                    // 已关闭则忽略
                                }
                                return;
                            }
                            sendSessionUpdate();
                            flushPending();
                        });
            } catch (Exception e) {
                if (done.compareAndSet(false, true)) {
                    listener.onError(e);
                }
            }
        }

        /** 连接就绪后补发积压音频与 finish（在 session.update 之后、任何后续音频之前）。 */
        private void flushPending() {
            List<byte[]> queued;
            boolean finish;
            synchronized (RealtimeSession.this) {
                queued = new ArrayList<>(pendingAudio);
                pendingAudio.clear();
                finish = finishRequested;
                finishRequested = false;
            }
            for (byte[] pcm : queued) {
                sendAppend(pcm);
            }
            if (finish) {
                sendCommitAndCreate();
            }
        }

        private void sendAppend(byte[] pcm) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "input_audio_buffer.append");
            o.addProperty("audio", Base64.getEncoder().encodeToString(pcm));
            send(o.toString());
        }

        private void sendCommitAndCreate() {
            // 提交缓冲并触发一次响应（响应过程中会产生输入音频转写）
            JsonObject commit = new JsonObject();
            commit.addProperty("type", "input_audio_buffer.commit");
            send(commit.toString());
            JsonObject create = new JsonObject();
            create.addProperty("type", "response.create");
            send(create.toString());
        }

        private void sendSessionUpdate() {
            JsonObject root = new JsonObject();
            root.addProperty("type", "session.update");
            JsonObject s = new JsonObject();
            JsonArray modalities = new JsonArray();
            modalities.add("text");                       // 只要文字输出，不要模型回话音频
            s.add("modalities", modalities);
            JsonObject iat = new JsonObject();
            iat.addProperty("model", model);
            s.add("input_audio_transcription", iat);       // 开启输入音频转写
            s.add("turn_detection", JsonNull.INSTANCE);    // 关服务端 VAD，由 feed/finish 手动控制
            root.add("session", s);
            send(root.toString());
        }

        /** 线程安全地把一条 JSON 文本发出去。 */
        private void send(String text) {
            WebSocket s = ws;
            if (s != null && !done.get()) {
                synchronized (RealtimeSession.this) {
                    if (ws != null && !done.get()) {
                        s.sendText(text, true);
                    }
                }
            }
        }

        @Override
        public void feed(byte[] pcm) {
            if (done.get()) {
                return;
            }
            synchronized (RealtimeSession.this) {
                if (ws == null) {          // 握手还没好：先积压，连接就绪后补发
                    pendingAudio.add(pcm);
                    return;
                }
            }
            sendAppend(pcm);
        }

        @Override
        public void finish() {
            if (done.get()) {
                return;
            }
            synchronized (RealtimeSession.this) {
                if (ws == null) {          // 握手还没好：记下 finish，连接就绪后补发
                    finishRequested = true;
                    return;
                }
            }
            sendCommitAndCreate();
        }

        @Override
        public void cancel() {
            if (done.compareAndSet(false, true) && ws != null) {
                try {
                    ws.sendClose(1000, "cancel");
                } catch (Exception ignored) {
                    // 连接已断也无妨
                }
            }
        }

        private final class WsListener implements WebSocket.Listener {

            private final StringBuilder transcript = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                WebSocket.Listener.super.onOpen(webSocket);
            }

            @Override
            public void onMessage(WebSocket webSocket, CharSequence data) {
                try {
                    JsonObject e = JsonParser.parseString(data.toString()).getAsJsonObject();
                    String type = e.has("type") ? e.get("type").getAsString() : "";
                    switch (type) {
                        case "conversation.item.input_audio_transcription.delta" -> {
                            if (e.has("transcript")) {
                                transcript.append(e.get("transcript").getAsString());
                                listener.onPartial(transcript.toString());
                            }
                        }
                        case "conversation.item.input_audio_transcription.completed" -> {
                            String t = e.has("transcript")
                                    ? e.get("transcript").getAsString() : transcript.toString();
                            if (done.compareAndSet(false, true)) {
                                listener.onFinal(t.strip());
                                closeQuietly();
                            }
                        }
                        case "error" -> {
                            String msg = "DashScope STT error";
                            if (e.has("error")) {
                                JsonObject err = e.getAsJsonObject("error");
                                if (err.has("message")) {
                                    msg = err.get("message").getAsString();
                                }
                            }
                            if (done.compareAndSet(false, true)) {
                                listener.onError(new IllegalStateException(msg));
                                closeQuietly();
                            }
                        }
                        default -> { /* session.* / response.* 等与识别无关，忽略 */ }
                    }
                } catch (RuntimeException ex) {
                    if (done.compareAndSet(false, true)) {
                        listener.onError(ex);
                    }
                }
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                if (done.compareAndSet(false, true)) {
                    listener.onError(error);
                }
            }

            @Override
            public void onClose(WebSocket webSocket, int statusCode, String reason) {
                // 若转写未完成连接就断了，给一个空结果而非永远挂起
                if (done.compareAndSet(false, true)) {
                    listener.onFinal(transcript.toString().strip());
                }
            }

            private void closeQuietly() {
                try {
                    ws.sendClose(1000, "done");
                } catch (Exception ignored) {
                    // 已关闭则忽略
                }
            }
        }
    }
}
