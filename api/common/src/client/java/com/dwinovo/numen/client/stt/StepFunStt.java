package com.dwinovo.numen.client.stt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 阶跃星辰(StepFun)语音识别:{@code POST {base}/v1/audio/asr/sse},私有 JSON 协议
 * (裸 PCM base64 进请求体)+ SSE 流式返回。事件三种:{@code transcript.text.delta}
 * (增量,转给 {@link SttListener#onPartial})、{@code transcript.text.done}
 * (全文,权威结果)、{@code error}。
 *
 * <p>会话是批量形态(缓冲 PCM,松开麦克风才上传)——StepFun 只有 HTTP+SSE 这一种
 * 调法,音频要一次性给全,SSE 只是让识别结果分段先到。我们的采集格式
 * (16k/16bit/单声道 s16le,{@link SttAudio#FORMAT})与它的 pcm 格式声明逐项一致,零转换。
 */
public final class StepFunStt implements SttBackend {

    private static final String SUFFIX = "/audio/asr/sse";
    public static final String DEFAULT_BASE = "https://api.stepfun.com";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            // 明文 http 下 JDK 默认发 h2c 升级头,自建服务端(Uvicorn 等)刷警告;钉死 1.1。
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final String url;
    private final String apiKey;
    private final String model;

    public StepFunStt(String baseUrl, String apiKey, String model) {
        this.url = composeUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
    }

    /** 宽容拼 URL,与 whisper-http 同规则:留空用官方缺省;补 scheme;去尾斜杠;
     *  已带完整路径直接用;带 /v1 只补后半;否则补 /v1/audio/asr/sse。 */
    static String composeUrl(String base) {
        String b = base == null ? "" : base.strip();
        if (b.isEmpty()) {
            b = DEFAULT_BASE;
        }
        if (!b.contains("://")) {
            b = "https://" + b;
        }
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.endsWith(SUFFIX)) {
            return b;
        }
        if (b.endsWith("/v1")) {
            return b + SUFFIX;
        }
        return b + "/v1" + SUFFIX;
    }

    @Override
    public SttSession open(SttListener listener) {
        return new BatchSession(listener);
    }

    @Override
    public String describe() {
        return "stepfun-asr(" + url + ", model=" + model + ")";
    }

    /** 组装请求体:裸 PCM base64 + 转写与格式声明。language 不发,交给服务端自检
     *  (硬写 zh 会把说英文的主人转坏;缺参错误会原文回给 onError,不静默)。 */
    JsonObject buildBody(byte[] pcm) {
        JsonObject transcription = new JsonObject();
        transcription.addProperty("model", model);
        JsonObject format = new JsonObject();
        format.addProperty("type", "pcm");
        format.addProperty("codec", "pcm_s16le");
        format.addProperty("rate", 16000);
        format.addProperty("bits", 16);
        format.addProperty("channel", 1);
        JsonObject input = new JsonObject();
        input.add("transcription", transcription);
        input.add("format", format);
        JsonObject audio = new JsonObject();
        audio.addProperty("data", Base64.getEncoder().encodeToString(pcm));
        audio.add("input", input);
        JsonObject body = new JsonObject();
        body.add("audio", audio);
        return body;
    }

    /** 缓冲整段 PCM,finish 时一次上传并逐行读 SSE。 */
    private final class BatchSession implements SttSession {

        private final SttListener listener;
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        private volatile boolean cancelled;

        BatchSession(SttListener listener) {
            this.listener = listener;
        }

        @Override
        public void feed(byte[] chunk) {
            if (!cancelled) {
                pcm.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public void finish() {
            if (cancelled) {
                return;
            }
            HttpRequest request;
            try {
                request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                buildBody(pcm.toByteArray()).toString(), StandardCharsets.UTF_8))
                        .build();
            } catch (RuntimeException e) {
                listener.onError(e);
                return;
            }
            CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(resp -> {
                        if (resp.statusCode() / 100 != 2) {
                            StringBuilder sb = new StringBuilder();
                            resp.body().limit(20).forEach(l -> sb.append(l).append('\n'));
                            listener.onError(new IllegalStateException(
                                    "StepFun ASR HTTP " + resp.statusCode() + ": " + sb.toString().strip()));
                            return;
                        }
                        SseTranscript t = new SseTranscript(new SseTranscript.Sink() {
                            @Override public void delta(String text) {
                                if (!cancelled) listener.onPartial(text);
                            }
                            @Override public void done(String text) {
                                if (!cancelled) listener.onFinal(text);
                            }
                            @Override public void error(String message) {
                                if (!cancelled) listener.onError(new IllegalStateException(message));
                            }
                        });
                        resp.body().forEach(t::acceptLine);
                        t.end();
                    })
                    .exceptionally(err -> {
                        if (!cancelled) listener.onError(err);
                        return null;
                    });
        }
    }

    /**
     * SSE 行解析(可单测,不碰网络)。失败方向与豆包同一条纪律:读不懂只能往"出错"倒,
     * 不能悄悄变成一句空转写。done 事件的全文是权威结果;流结束还没见过 done = 协议
     * 没走完,报错并附上原始行帮排查。
     */
    static final class SseTranscript {

        interface Sink {
            void delta(String text);

            void done(String text);

            void error(String message);
        }

        private final Sink sink;
        private String event = "";
        private boolean finished;
        private String lastRaw = "";

        SseTranscript(Sink sink) {
            this.sink = sink;
        }

        void acceptLine(String line) {
            if (finished) {
                return;
            }
            if (line.startsWith("event:")) {
                event = line.substring(6).strip();
                return;
            }
            if (line.startsWith("data:")) {
                lastRaw = line.substring(5).strip();
                dispatch(event, lastRaw);
                return;
            }
            if (line.isBlank()) {
                event = "";
            }
        }

        /** 流走完了还没有 done:协议没走完,按错误收场(带原始行)。 */
        void end() {
            if (!finished) {
                finished = true;
                sink.error("StepFun ASR SSE 流结束但没有 transcript.text.done;最后一条: " + lastRaw);
            }
        }

        private void dispatch(String ev, String raw) {
            JsonObject data;
            try {
                data = JsonParser.parseString(raw).getAsJsonObject();
            } catch (RuntimeException e) {
                finished = true;
                sink.error("StepFun ASR 事件不是 JSON: " + raw);
                return;
            }
            switch (ev) {
                case "transcript.text.delta" -> {
                    String text = textOf(data);
                    if (text != null && !text.isEmpty()) {
                        sink.delta(text);
                    }
                }
                case "transcript.text.done" -> {
                    finished = true;
                    String text = textOf(data);
                    if (text == null) {
                        sink.error("StepFun ASR done 事件里找不到文本字段: " + raw);
                    } else {
                        sink.done(text);
                    }
                }
                case "error" -> {
                    finished = true;
                    sink.error("StepFun ASR 报错: " + raw);
                }
                default -> { /* 心跳/未知事件:忽略,等 done */ }
            }
        }

        /** 事件体里的文本字段。文档没钉死字段名,按 delta/text/result 认;都没有返回 null 让上层报错。 */
        private static String textOf(JsonObject data) {
            for (String k : new String[]{"delta", "text", "result"}) {
                if (data.has(k) && data.get(k).isJsonPrimitive()) {
                    return data.get(k).getAsString();
                }
            }
            return null;
        }
    }
}
