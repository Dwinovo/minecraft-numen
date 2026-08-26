package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 豆包(火山引擎)大模型语音合成：{@code POST {base}/api/v3/tts/unidirectional}
 * ——HTTP Chunked 单向流式接口。
 *
 * <p>官方给了四个入口,这里选它:我们的契约是"一句文本进、一段完整 WAV 出",一次 POST 就够。
 * 两个 WebSocket 接口要自己实现分帧与 {@code StartConnection→StartSession→…} 会话状态机;
 * 异步 {@code submit}/{@code query} 是给十万字长文本做的,提交后还要轮询再下载,给一句对白用
 * 是三次往返起步。流式本身的首包优势对我们也没用:文本已被 {@link SentenceDivider} 切成短句,
 * 管线整段排队播,半句音频播不了。
 *
 * <p>音频取 {@code pcm} 而不是默认的 {@code mp3}——JDK 没有 mp3 解码器,为一个后端引一整个
 * 解码库不值当;裸 PCM 交给 {@link WavCodec#encodeMono16} 补 44 字节头即可。24kHz 落在
 * {@code WavCodec} 支持的 8k–48k 内,不重采样。
 *
 * <p><b>返回体是 NDJSON</b>(一行一个 JSON 对象),不是一整个 JSON——整体 parse 必炸。
 * {@code data} 是 base64 音频分片,{@code code=}{@value #CODE_DONE} 是正常收尾包,
 * 其余非零码是错误(如 45000000 音色鉴权失败、40000000 参数错误)。
 *
 * <p>端点文档：<a href="https://www.volcengine.com/docs/6561/1257584">大模型语音合成 API</a>
 *
 * <p>字段映射:{@code apiKey}={@code X-Api-Key}(控制台 &gt; API Key 管理)、
 * {@code model}={@code X-Api-Resource-Id}(空则 {@value #DEFAULT_RESOURCE_ID})、
 * {@code voice}={@code req_params.speaker}(控制台 &gt; 音色库的音色 ID)。
 * 音色必须与资源 ID 同代——2.0 的音色配 {@code seed-tts-2.0}、复刻音色配
 * {@code seed-icl-2.0},配错服务端报音色鉴权失败。
 */
public final class DoubaoTts implements TtsBackend {

    private static final String PATH = "/api/v3/tts/unidirectional";

    /** 缺省端点——火山引擎语音开放平台。 */
    public static final String DEFAULT_BASE = "https://openspeech.bytedance.com";

    /** 缺省资源 ID:豆包语音合成大模型 2.0(另一个可选值是声音复刻的 {@code seed-icl-2.0})。 */
    public static final String DEFAULT_RESOURCE_ID = "seed-tts-2.0";

    /** 采样率。文档允许 8k–48k,取 24k(官方推荐值,也是 WavCodec 支持区间内)。 */
    static final int SAMPLE_RATE = 24_000;

    /** 正常收尾包的状态码;音频分片是 0。 */
    static final long CODE_DONE = 20_000_000L;

    private final String url;
    private final String apiKey;
    private final String resourceId;
    private final String speaker;

    /**
     * @param baseUrl    站点根或完整路径，空串用缺省端点
     * @param apiKey     X-Api-Key
     * @param resourceId X-Api-Resource-Id（空串用缺省 {@value #DEFAULT_RESOURCE_ID}）
     * @param speaker    音色 ID，<b>必填</b>——没有安全的缺省值,填错的代价是服务端报鉴权失败
     */
    public DoubaoTts(String baseUrl, String apiKey, String resourceId, String speaker) {
        this.url = composeUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.resourceId = (resourceId == null || resourceId.isBlank())
                ? DEFAULT_RESOURCE_ID : resourceId.strip();
        this.speaker = speaker == null ? "" : speaker.strip();
    }

    /** 宽容拼 URL：留空用缺省端点；补默认 scheme；去尾斜杠；已带路径直接用,否则补上。 */
    static String composeUrl(String base) {
        String b = VoiceHttp.ensureScheme(base);
        if (b.isEmpty()) b = DEFAULT_BASE;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b.endsWith(PATH) ? b : b + PATH;
    }

    /**
     * 构建请求 body（纯函数，可测）。只放文档标必选的 {@code req_params};{@code user.uid}
     * 是服务端侧的调用方标识,官方示例里带着,给个常量即可。
     */
    static JsonObject buildBody(String text, String speaker) {
        JsonObject audio = new JsonObject();
        audio.addProperty("format", "pcm");
        audio.addProperty("sample_rate", SAMPLE_RATE);

        JsonObject params = new JsonObject();
        params.addProperty("text", text);
        params.addProperty("speaker", speaker);
        params.add("audio_params", audio);

        JsonObject user = new JsonObject();
        user.addProperty("uid", "numen");

        JsonObject body = new JsonObject();
        body.add("user", user);
        body.add("req_params", params);
        return body;
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text) {
        if (apiKey.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("豆包 TTS 未填写 API Key"));
        }
        if (speaker.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("豆包 TTS 未填写音色 ID"));
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(VoiceHttp.uriOf(url))
                    .timeout(VoiceHttp.REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", apiKey)
                    .header("X-Api-Resource-Id", resourceId)
                    .header("X-Api-Request-Id", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            buildBody(text, speaker).toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return VoiceHttp.CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new IllegalStateException(
                                VoiceHttp.humanHttpError("豆包 TTS", resp.statusCode(), resp.body()));
                    }
                    try {
                        return WavCodec.encodeMono16(parseChunks(resp.body()), SAMPLE_RATE);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /**
     * NDJSON → 拼接好的裸 PCM（纯函数，可测）。逐行独立解析:带 {@code data} 的是音频分片,
     * 收到 {@value #CODE_DONE} 即收工,其余非零码带着服务端的 message 抛出去。
     */
    static byte[] parseChunks(String body) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        String lastMessage = "";
        int lines = 0;
        for (String raw : (body == null ? "" : body).split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            lines++;
            JsonObject o;
            try {
                o = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IllegalStateException("豆包 TTS 返回了无法解析的数据", e);
            }
            if (o.has("message") && o.get("message").isJsonPrimitive()) {
                lastMessage = o.get("message").getAsString();
            }
            if (o.has("data") && o.get("data").isJsonPrimitive()) {
                String encoded = o.get("data").getAsString();
                if (!encoded.isBlank()) {
                    try {
                        pcm.writeBytes(Base64.getDecoder().decode(encoded));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException("豆包 TTS 返回了无效的 base64 音频", e);
                    }
                }
            }
            long code = o.has("code") && o.get("code").isJsonPrimitive()
                    ? o.get("code").getAsLong() : 0L;
            if (code == CODE_DONE) break;
            if (code != 0L) {
                throw new IllegalStateException("豆包 TTS 错误 " + code + detail(lastMessage));
            }
        }
        if (lines == 0) {
            throw new IllegalStateException("豆包 TTS 返回为空");
        }
        if (pcm.size() == 0) {
            throw new IllegalStateException("豆包 TTS 没有返回音频" + detail(lastMessage));
        }
        return pcm.toByteArray();
    }

    private static String detail(String message) {
        return message.isBlank() ? "" : ": " + message;
    }

    @Override
    public String describe() {
        return "doubao(" + url + ", resource=" + resourceId + ", speaker=" + speaker + ")";
    }
}
