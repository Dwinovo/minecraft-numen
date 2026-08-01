package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * MiniMax 语音合成(t2a_v2):{@code POST {base}/v1/t2a_v2},Bearer 鉴权,
 * body {@code {model, text, voice_setting:{voice_id}, audio_setting:{format:"wav",...},
 * output_format:"hex"}}。
 *
 * <p>与裸字节后端不同,<b>响应是 JSON、音频在 {@code data.audio} 字段里以
 * hex 字符串编码</b>,且 HTTP 200 也可能在 {@code base_resp.status_code} 里带
 * 业务错误(0 = 成功)——解析与 hex 解码见 {@link #extractAudio}。
 * 请求 {@code format:"wav"},解出的字节直接进 {@link WavCodec}。
 *
 * <p>URL:官方国际站 {@code https://api.minimax.io}(大陆主站
 * {@code https://api.minimaxi.com});旧版接入需要 {@code ?GroupId=} 查询参数,
 * 新版文档已不要求——{@code groupId} 留空即不携带,填了就拼上,两代接入都吃。
 */
public final class MiniMaxTts implements TtsBackend {

    private static final String T2A_SUFFIX = "/v1/t2a_v2";
    /** 官方端点(大陆主站)——URL 留空即用它,表单选型时也预填它;
     *  国际站账号手动改成 https://api.minimax.io(两站的 key 不通用)。 */
    public static final String DEFAULT_BASE = "https://api.minimaxi.com";
    /** model 留空时的缺省(官方当前的低时延档)。 */
    static final String DEFAULT_MODEL = "speech-02-turbo";

    private final String url;
    private final String apiKey;
    private final String model;
    private final String voiceId;

    public MiniMaxTts(String baseUrl, String apiKey, String groupId, String model, String voiceId) {
        this.url = composeUrl(baseUrl, groupId);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.strip();
        this.voiceId = voiceId == null ? "" : voiceId;
    }

    /** 宽容拼 URL:留空用官方端点,补默认 scheme,去尾斜杠,没带 /t2a_v2 就补 /v1/t2a_v2;groupId 非空拼成查询参数。 */
    static String composeUrl(String base, String groupId) {
        String b = VoiceHttp.ensureScheme(base);
        if (b.isEmpty()) b = DEFAULT_BASE;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (!b.endsWith("/t2a_v2")) b = b + T2A_SUFFIX;
        String g = groupId == null ? "" : groupId.strip();
        if (!g.isEmpty()) b = b + "?GroupId=" + g;
        return b;
    }

    /** 请求 body(纯函数,可测):wav 单声道 32k,hex 输出,非流式。 */
    static JsonObject buildBody(String model, String voiceId, String text) {
        JsonObject voice = new JsonObject();
        voice.addProperty("voice_id", voiceId);
        JsonObject audio = new JsonObject();
        audio.addProperty("format", "wav");
        audio.addProperty("sample_rate", 32_000);
        audio.addProperty("channel", 1);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("text", text);
        body.addProperty("stream", false);
        body.addProperty("output_format", "hex");
        body.add("voice_setting", voice);
        body.add("audio_setting", audio);
        return body;
    }

    /**
     * 解析 t2a_v2 的 JSON 响应,取出 hex 编码的音频并解码为 WAV 字节。
     * {@code base_resp.status_code != 0} 或缺 {@code data.audio} 时抛
     * {@link IllegalStateException},错误原文(status_msg)进消息。
     */
    static byte[] extractAudio(String responseJson) {
        JsonObject root = JsonParser.parseString(responseJson).getAsJsonObject();
        if (root.has("base_resp") && root.get("base_resp").isJsonObject()) {
            JsonObject br = root.getAsJsonObject("base_resp");
            int code = br.has("status_code") ? br.get("status_code").getAsInt() : 0;
            if (code != 0) {
                String msg = br.has("status_msg") ? br.get("status_msg").getAsString() : "?";
                throw new IllegalStateException("MiniMax status_code " + code + ": " + msg);
            }
        }
        if (!root.has("data") || !root.get("data").isJsonObject()
                || !root.getAsJsonObject("data").has("audio")) {
            throw new IllegalStateException("MiniMax 响应缺少 data.audio 字段");
        }
        return hexDecode(root.getAsJsonObject("data").get("audio").getAsString());
    }

    /** hex 字符串 → 字节。非偶数长度 / 非 hex 字符抛 {@link IllegalStateException}。 */
    static byte[] hexDecode(String hex) {
        String s = hex == null ? "" : hex.strip();
        if ((s.length() & 1) != 0) {
            throw new IllegalStateException("hex 音频长度为奇数(" + s.length() + ")");
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalStateException("hex 音频含非法字符(位置 " + i * 2 + ")");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(VoiceHttp.uriOf(url))
                    .timeout(VoiceHttp.REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            buildBody(model, voiceId, text).toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);   // 坏配置走异步失败通道,绝不同步炸
        }

        return VoiceHttp.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new IllegalStateException(VoiceHttp.humanHttpError("MiniMax",
                                resp.statusCode(), resp.body()));
                    }
                    return extractAudio(resp.body());
                });
    }

    @Override
    public String describe() {
        return "minimax(" + url + ", model=" + model + ", voice=" + voiceId + ")";
    }
}
