package com.dwinovo.numen.client.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * 小米 Mimo 语音合成：{@code POST {base}/v1/chat/completions}，
 * body {@code {model, messages:[{role:"user",content:"音色描述"},
 * {role:"assistant",content:"待合成文本"}], audio:{format,voice}}}，Bearer 或 api-key 鉴权。
 *
 * <p>与标准 OpenAI TTS ({@code /v1/audio/speech}) 不同，Mimo 的 TTS 走
 * <b>Chat Completions 协议</b>：{@code messages} 数组中 user 消息描述音色风格，
 * assistant 消息为待合成文本；响应结构与 Chat 响应一致，音频藏在
 * {@code choices[0].message.audio.data}（base64 编码）。
 *
 * <p>端点文档：<a href="https://mimo.mi.com/docs/zh-CN/api/audio/tts">Mimo TTS API</a>
 *
 * <p>URL 宽容规则：留空用缺省端点；补默认 scheme；去尾斜杠；已带
 * {@code /chat/completions} 直接用；带 {@code /v1} 补 {@code /chat/completions}；
 * 否则补 {@code /v1/chat/completions}。
 *
 * <p>鉴权：{@code api-key: <key>} 请求头（官方文档两种鉴权二选一,取其首选;
 * 双头齐发有被服务端拒的风险,不做兼容兜底）。
 */
public final class MimoTts implements TtsBackend {

    private static final String CHAT_SUFFIX = "/chat/completions";

    /** 缺省端点——小米 Mimo 官方 API。 */
    public static final String DEFAULT_BASE = "https://api.xiaomimimo.com";

    /** Mimo 推荐模型（V2.5 系列，V2 已下线）。 */
    public static final String DEFAULT_MODEL = "mimo-v2.5-tts";

    /** 默认音色。 */
    public static final String DEFAULT_VOICE = "mimo_default";

    private final String url;
    private final String apiKey;
    private final String model;
    private final String voice;

    /**
     * @param baseUrl  站点根或完整路径，空串用缺省端点
     * @param apiKey   鉴权令牌
     * @param model    TTS 模型 id（空串用缺省 {@value #DEFAULT_MODEL}）
     * @param voice    音色名称（空串用缺省 {@value #DEFAULT_VOICE}）
     */
    public MimoTts(String baseUrl, String apiKey, String model, String voice) {
        this.url = composeUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
        this.voice = (voice == null || voice.isBlank()) ? DEFAULT_VOICE : voice;
    }

    /**
     * 宽容拼 URL：留空用缺省端点；补默认 scheme；去尾斜杠；
     * 已带 {@code /chat/completions} 直接用；带 {@code /v1} 补后半；否则补
     * {@code /v1/chat/completions}。
     */
    static String composeUrl(String base) {
        String b = VoiceHttp.ensureScheme(base);
        if (b.isEmpty()) b = DEFAULT_BASE;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith(CHAT_SUFFIX)) return b;
        if (b.endsWith("/v1")) return b + CHAT_SUFFIX;
        return b + "/v1" + CHAT_SUFFIX;
    }

    /**
     * 构建请求 body（纯函数，可测）。
     * <p>Mimo TTS 走 Chat Completions 协议：
     * <ul>
     *   <li>{@code messages[0]} — role=user，content 为音色/风格描述（可留空）；</li>
     *   <li>{@code messages[1]} — role=assistant，content 为待合成文本；</li>
     *   <li>{@code audio} — {format, voice} 音频输出配置。</li>
     * </ul>
     */
    static JsonObject buildBody(String model, String voice, String text) {
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", "");    // 音色描述——简单场景留空，用 voice 字段选预设

        JsonObject assistantMsg = new JsonObject();
        assistantMsg.addProperty("role", "assistant");
        assistantMsg.addProperty("content", text);

        JsonArray messages = new JsonArray();
        messages.add(userMsg);
        messages.add(assistantMsg);

        JsonObject audio = new JsonObject();
        audio.addProperty("format", "wav");
        audio.addProperty("voice", voice);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.add("audio", audio);
        return body;
    }

    /**
     * 解析 Mimo Chat Completions 响应，取出 base64 编码的音频并解码为 WAV 字节。
     * <p>响应结构：{@code choices[0].message.audio.data}（base64 字符串）。
     * 缺字段或解析失败时抛 {@link IllegalStateException}。
     */
    static byte[] extractAudio(String responseJson) {
        JsonObject root = JsonParser.parseString(responseJson).getAsJsonObject();

        // 错误检查——Mimo 遵循 OpenAI 错误格式
        if (root.has("error")) {
            JsonObject error = root.getAsJsonObject("error");
            String msg = error.has("message") ? error.get("message").getAsString() : "?";
            throw new IllegalStateException("Mimo API error: " + msg);
        }

        if (!root.has("choices") || !root.get("choices").isJsonArray()) {
            throw new IllegalStateException("Mimo 响应缺少 choices 数组");
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices.isEmpty()) {
            throw new IllegalStateException("Mimo 响应 choices 为空");
        }

        JsonObject choice = choices.get(0).getAsJsonObject();
        if (!choice.has("message") || !choice.get("message").isJsonObject()) {
            throw new IllegalStateException("Mimo 响应缺少 message 字段");
        }
        JsonObject message = choice.getAsJsonObject("message");

        if (!message.has("audio") || !message.get("audio").isJsonObject()) {
            throw new IllegalStateException("Mimo 响应缺少 audio 字段");
        }
        JsonObject audio = message.getAsJsonObject("audio");

        if (!audio.has("data") || audio.get("data").isJsonNull()) {
            throw new IllegalStateException("Mimo 响应缺少 audio.data 字段");
        }

        String b64 = audio.get("data").getAsString();
        if (b64 == null || b64.isBlank()) {
            throw new IllegalStateException("Mimo audio.data 为空");
        }
        return Base64.getDecoder().decode(b64);
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(VoiceHttp.uriOf(url))
                    .timeout(VoiceHttp.REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("api-key", apiKey)   // 官方两种鉴权二选一,取文档首选
                    .POST(HttpRequest.BodyPublishers.ofString(
                            buildBody(model, voice, text).toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);   // 坏配置走异步失败通道,绝不同步炸
        }

        // 响应是 JSON（非裸字节），需解析后 base64 解码
        return VoiceHttp.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new IllegalStateException(VoiceHttp.humanHttpError("Mimo-TTS",
                                resp.statusCode(), resp.body()));
                    }
                    return extractAudio(resp.body());
                });
    }

    @Override
    public String describe() {
        return "mimo(" + url + ", model=" + model + ", voice=" + voice + ")";
    }
}
