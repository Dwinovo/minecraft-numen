package com.dwinovo.numen.client.voice;

import com.dwinovo.numen.client.data.JsonLibrary;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家的命名声线库,存于 {@code config/numen/voice.json}——设置面板"语音"tab
 * 背后的数据层,存储与 API 形状对齐 {@code ProviderLibrary}(模型配置库):
 * 两段式 = <b>声线条目</b>(命名的 TTS 配置)+ <b>每同伴绑定</b>(uuid → 条目 id),
 * 外加一个全局总开关。没绑定/关总开关的同伴静音,零开销。客户端单例。
 *
 * <h2>文件样例</h2>
 * <pre>{@code
 * {
 *   "enabled": true,
 *   "entries": [
 *     { "id": "voice_18c2f3a_0", "name": "硅基流动·Alex",
 *       "backend": "openai", "url": "https://api.siliconflow.cn",
 *       "api_key": "sk-xxxx", "model": "FunAudioLLM/CosyVoice2-0.5B",
 *       "voice": "FunAudioLLM/CosyVoice2-0.5B:alex", "volume": 1.0 },
 *     { "id": "voice_18c2f3b_1", "name": "本地派蒙",
 *       "backend": "gpt_sovits", "url": "http://127.0.0.1:9880",
 *       "ref_audio": "D:/voices/paimon_ref.wav", "prompt_text": "参考音频里的那句话",
 *       "text_lang": "zh", "volume": 1.2 }
 *   ],
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code backend} — {@code "openai"}(OpenAI /v1/audio/speech 协议,含硅基流动等)、
 *       {@code "mimo"}(小米 Mimo TTS,Chat Completions 风格私有协议,字段:
 *       url/api_key/model/voice)、
 *       {@code "doubao"}(豆包大模型语音合成,单向流式 NDJSON,字段:
 *       url/api_key/model=资源 ID/voice=音色 ID)、
 *       {@code "gpt_sovits"}(api_v2 的 /tts)、{@code "minimax"}(t2a_v2,字段:
 *       url/api_key/group_id 可选/model/voice=voice_id)或 {@code "fish_audio"}
 *       (v1/tts,字段:url/api_key/voice=reference_id/model=可选模型头);</li>
 *   <li>{@code volume} — 0.0–2.0,缺省 1.0(&gt;1 扩大可听半径,响度上限仍是 1)。</li>
 * </ul>
 */
public final class VoiceLibrary extends JsonLibrary<VoiceLibrary.Entry> {

    /** 后端的标识串(存储与表单下拉共用)。未知值按 openai 兜底。 */
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

    /**
     * 一条命名声线配置。允许不完整——只有名字是必填;参数错误在第一次合成时以日志报错。
     * 字段按后端复用:{@code voice} 在 openai 是音色 id、在 minimax 是 voice_id、
     * 在 fish_audio 是 reference_id;{@code groupId} 仅 minimax 用(旧版接入的
     * 查询参数,可空);{@code refAudio/promptText/textLang} 仅 gpt_sovits 用。
     */
    public record Entry(String id, String name, String backend, String url, String apiKey,
                        String groupId, String model, String voice, String refAudio,
                        String promptText, String textLang, float volume) {

        public boolean isSovits() {
            return BACKEND_SOVITS.equalsIgnoreCase(backend);
        }

        public boolean isMiniMax() {
            return BACKEND_MINIMAX.equalsIgnoreCase(backend);
        }

        public boolean isFishAudio() {
            return BACKEND_FISH.equalsIgnoreCase(backend);
        }

        public boolean isDashScope() {
            return BACKEND_DASHSCOPE.equalsIgnoreCase(backend);
        }

        public boolean isMimo() {
            return BACKEND_MIMO.equalsIgnoreCase(backend);
        }

        public boolean isDoubao() {
            return BACKEND_DOUBAO.equalsIgnoreCase(backend);
        }

        /** 据 backend 字段实例化对应 TTS 实现。 */
        public TtsBackend createBackend() {
            if (isSovits()) {
                return new GptSovitsTts(url, refAudio, promptText, textLang);
            }
            if (isMiniMax()) {
                return new MiniMaxTts(url, apiKey, groupId, model, voice);
            }
            if (isFishAudio()) {
                return new FishAudioTts(url, apiKey, voice, model);
            }
            if (isDashScope()) {
                return new DashScopeTts(url, apiKey, model, voice);
            }
            if (isMimo()) {
                return new MimoTts(url, apiKey, model, voice);
            }
            if (isDoubao()) {
                return new DoubaoTts(url, apiKey, model, voice);
            }
            return new OpenAiCompatibleTts(url, apiKey, model, voice);
        }
    }

    private static VoiceLibrary instance;

    private boolean enabled;

    /** 测试可直接用临时路径构造;游戏内走 {@link #instance()}。 */
    VoiceLibrary(Path file) {
        super(file);
        load();
    }

    public static VoiceLibrary instance() {
        if (instance == null) {
            instance = new VoiceLibrary(configDir().resolve("voice.json"));
        }
        return instance;
    }

    // ---- global switch ----

    /** 全局语音总开关(缺省 true)。关闭时 {@link #resolve} 一律返回 null。 */
    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        save();
    }

    // ---- entries ----

    /** 新建声线——只有名字必填,其余可空;持久化并返回。 */
    public Entry create(String name, String backend, String url, String apiKey, String groupId,
                        String model, String voice, String refAudio, String promptText,
                        String textLang, float volume) {
        Entry e = new Entry(freshId("voice"), name, backend, url, apiKey, groupId, model, voice,
                refAudio, promptText, textLang, clampVolume(volume));
        putAndSave(e);
        return e;
    }

    public void update(Entry e) {
        if (e == null || !entries.containsKey(e.id())) return;
        putAndSave(new Entry(e.id(), e.name(), e.backend(), e.url(), e.apiKey(),
                e.groupId(), e.model(), e.voice(), e.refAudio(), e.promptText(), e.textLang(),
                clampVolume(e.volume())));
    }

    /**
     * 语音管线的唯一入口:这个同伴此刻应该用哪条声线。全局开关关闭、
     * 未绑定、或绑定指向已删除的条目,都返回 null(= 静音)。
     * 删除声线时指向它的绑定保持原样(悬空绑定得 null = 静音),与模型配置库同语义。
     */
    public Entry resolve(UUID companion) {
        if (!enabled || companion == null) return null;
        return get(com.dwinovo.numen.client.agent.CompanionHome.binding(companion).voiceId());
    }

    // ---- pending summon assignment (same mechanism as PersonaLibrary.pendSummon:
    // the new companion's UUID is unknown until the roster snapshot arrives) ----

    private static final Map<String, String> PENDING_SUMMON = new LinkedHashMap<>();

    /** 召唤时选的声线,按名字暂存(新同伴的 UUID 要等 CompanionListPayload 才知道)。 */
    public static void pendSummon(String name, String entryId) {
        if (name == null || entryId == null) return;
        PENDING_SUMMON.put(name, entryId);
    }

    /** 取走(并清除)刚到货同伴名下暂存的声线 id,无则 null。 */
    public static String takePendingSummon(String name) {
        return PENDING_SUMMON.remove(name);
    }

    // ---- persistence hooks ----

    @Override
    protected String logTag() {
        return "numen-voice";
    }

    @Override
    protected String idOf(Entry e) {
        return e.id();
    }

    @Override
    protected Entry readEntry(JsonObject o) {
        return new Entry(str(o, "id"), str(o, "name"), str(o, "backend"),
                str(o, "url"), str(o, "api_key"), str(o, "group_id"),
                str(o, "model"), str(o, "voice"),
                str(o, "ref_audio"), str(o, "prompt_text"), str(o, "text_lang"),
                clampVolume(o.has("volume") ? o.get("volume").getAsFloat() : 1.0f));
    }

    @Override
    protected JsonObject writeEntry(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id());
        o.addProperty("name", e.name());
        if (nb(e.backend())) o.addProperty("backend", e.backend());
        if (nb(e.url())) o.addProperty("url", e.url());
        if (nb(e.apiKey())) o.addProperty("api_key", e.apiKey());
        if (nb(e.groupId())) o.addProperty("group_id", e.groupId());
        if (nb(e.model())) o.addProperty("model", e.model());
        if (nb(e.voice())) o.addProperty("voice", e.voice());
        if (nb(e.refAudio())) o.addProperty("ref_audio", e.refAudio());
        if (nb(e.promptText())) o.addProperty("prompt_text", e.promptText());
        if (nb(e.textLang())) o.addProperty("text_lang", e.textLang());
        o.addProperty("volume", e.volume());
        return o;
    }

    @Override
    protected void readExtra(JsonObject root) {
        enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
    }

    @Override
    protected void writeExtra(JsonObject root) {
        root.addProperty("enabled", enabled);
    }

    @Override
    protected void resetExtra() {
        enabled = true;   // 缺省开——玩家配好声线就该出声,关闭是显式选择
    }

    @Override
    protected void resetOnCorrupt() {
        enabled = false;   // 坏文件不出声:宁静音,不拿半截配置乱合成
    }

    /** 音量夹到 0–2(NaN → 1);GUI 表单与加载路径共用。 */
    public static float clampVolume(float v) {
        if (Float.isNaN(v)) return 1.0f;
        return Math.max(0f, Math.min(2f, v));
    }
}
