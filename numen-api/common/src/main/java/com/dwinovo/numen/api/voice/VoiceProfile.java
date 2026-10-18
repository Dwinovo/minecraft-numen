package com.dwinovo.numen.api.voice;

import com.dwinovo.numen.api.library.LibraryKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 声线这种库({@link #KIND}):一条一个 {@code voice/<命名空间>/<名字>.json},写的是用哪家语音服务、哪个音色、什么参数——
 * <b>不含 key</b>。key 按"服务"({@link #keyService})存在<b>这台电脑的</b>钥匙串里:合成发生在主人的客户端,key 不离开他的机器。
 * 所以内置的声线可以带"推荐的音色",玩家填上对应服务的 key 就能用。玩家看到"内置"与"我的"两组,存储、开关、同步都是
 * {@link com.dwinovo.numen.api.library.Library} 这一套;她用哪条记在她的绑定里。
 *
 * <p>字段按后端复用:{@code voice} 在 openai 是音色 id、在 minimax 是 voice_id、在 fish_audio 是 reference_id;{@code groupId}
 * 仅 minimax 用;{@code refAudio/promptText/textLang} 仅 gpt_sovits 用(路径是客户端那台电脑上的)。
 *
 * <ul>
 *   <li>{@code backend} — {@code "openai"}(OpenAI /v1/audio/speech 协议,含硅基流动等)、{@code "mimo"}、{@code "doubao"}、
 *       {@code "gpt_sovits"}、{@code "minimax"}、{@code "fish_audio"}、{@code "dashscope"}。不认得的当 openai;</li>
 *   <li>{@code service} — 用钥匙串里哪个服务的 key;不写就是后端的标识。同一个服务只有一把;</li>
 *   <li>{@code volume} — 0.0–2.0,缺省 1.0(&gt;1 扩大可听半径,响度上限仍是 1)。</li>
 * </ul>
 */
public record VoiceProfile(String service, String backend, String url, String groupId, String model, String voice,
                           String refAudio, String promptText, String textLang, float volume) {

    /** 声线这种库:一条一个 {@code .json}。 */
    public static final LibraryKind KIND = LibraryKind.files("voice", ".json");

    public static final String BACKEND_OPENAI = "openai";
    public static final String BACKEND_SOVITS = "gpt_sovits";
    public static final String BACKEND_MINIMAX = "minimax";
    public static final String BACKEND_FISH = "fish_audio";
    /** 阿里云百炼(DashScope)实时语音合成:WebSocket 流式,不是 REST。 */
    public static final String BACKEND_DASHSCOPE = "dashscope";
    /** 小米 Mimo TTS:OpenAI 兼容协议,自有模型与音色体系。 */
    public static final String BACKEND_MIMO = "mimo";
    /** 豆包(火山引擎)大模型语音合成:HTTP Chunked 单向流式,返回 NDJSON。 */
    public static final String BACKEND_DOUBAO = "doubao";

    public VoiceProfile {
        backend = switch (backend == null ? "" : backend.toLowerCase(java.util.Locale.ROOT)) {
            case BACKEND_SOVITS -> BACKEND_SOVITS;
            case BACKEND_MINIMAX -> BACKEND_MINIMAX;
            case BACKEND_FISH -> BACKEND_FISH;
            case BACKEND_DASHSCOPE -> BACKEND_DASHSCOPE;
            case BACKEND_MIMO -> BACKEND_MIMO;
            case BACKEND_DOUBAO -> BACKEND_DOUBAO;
            default -> BACKEND_OPENAI;
        };
        service = blank(service) ? "" : service.strip();
        url = nonNull(url);
        groupId = nonNull(groupId);
        model = nonNull(model);
        voice = nonNull(voice);
        refAudio = nonNull(refAudio);
        promptText = nonNull(promptText);
        textLang = nonNull(textLang);
        volume = clampVolume(volume);
    }

    /** 这条声线的 key 在钥匙串里叫什么:写了服务就是它,没写就是后端的标识。 */
    public String keyService() {
        return service.isEmpty() ? backend : service;
    }

    /** 读一个条目文件的正文;写法不对抛 {@link RuntimeException}。 */
    public static VoiceProfile read(String json) {
        return fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    /** 请求体或条目文件里的一条声线。 */
    public static VoiceProfile fromJson(JsonObject o) {
        return new VoiceProfile(string(o, "service"), string(o, "backend"), string(o, "url"), string(o, "group_id"),
                string(o, "model"), string(o, "voice"), string(o, "ref_audio"), string(o, "prompt_text"),
                string(o, "text_lang"), o.has("volume") && o.get("volume").isJsonPrimitive()
                        ? o.get("volume").getAsFloat() : 1.0f);
    }

    /** 条目文件的正文。 */
    public String write() {
        return toJson().toString();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        put(o, "service", service);
        o.addProperty("backend", backend);
        put(o, "url", url);
        put(o, "group_id", groupId);
        put(o, "model", model);
        put(o, "voice", voice);
        put(o, "ref_audio", refAudio);
        put(o, "prompt_text", promptText);
        put(o, "text_lang", textLang);
        o.addProperty("volume", volume);
        return o;
    }

    /** 音量夹到 0–2(NaN → 1);表单与加载路径共用。 */
    public static float clampVolume(float v) {
        if (Float.isNaN(v)) {
            return 1.0f;
        }
        return Math.max(0f, Math.min(2f, v));
    }

    private static void put(JsonObject o, String key, String value) {
        if (!value.isEmpty()) {
            o.addProperty(key, value);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nonNull(String s) {
        return s == null ? "" : s.strip();
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
