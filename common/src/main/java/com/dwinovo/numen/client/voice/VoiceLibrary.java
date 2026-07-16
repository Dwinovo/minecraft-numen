package com.dwinovo.numen.client.voice;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   "assignments": { "<同伴uuid>": "voice_18c2f3a_0" }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code backend} — {@code "openai"}(OpenAI /v1/audio/speech 协议,含硅基流动等)、
 *       {@code "gpt_sovits"}(api_v2 的 /tts)、{@code "minimax"}(t2a_v2,字段:
 *       url/api_key/group_id 可选/model/voice=voice_id)或 {@code "fish_audio"}
 *       (v1/tts,字段:url/api_key/voice=reference_id/model=可选模型头);</li>
 *   <li>{@code volume} — 0.0–2.0,缺省 1.0(&gt;1 扩大可听半径,响度上限仍是 1)。</li>
 * </ul>
 */
public final class VoiceLibrary {

    /** 四种后端的标识串(存储与表单下拉共用)。未知值按 openai 兜底。 */
    public static final String BACKEND_OPENAI = "openai";
    public static final String BACKEND_SOVITS = "gpt_sovits";
    public static final String BACKEND_MINIMAX = "minimax";
    public static final String BACKEND_FISH = "fish_audio";

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
            return new OpenAiCompatibleTts(url, apiKey, model, voice);
        }
    }

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static VoiceLibrary instance;

    private final Path file;
    private boolean enabled;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, String> assignments = new LinkedHashMap<>();

    /** 测试可直接用临时路径构造;游戏内走 {@link #instance()}。 */
    VoiceLibrary(Path file) {
        this.file = file;
        load();
    }

    public static VoiceLibrary instance() {
        if (instance == null) {
            Path dir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("numen");
            instance = new VoiceLibrary(dir.resolve("voice.json"));
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

    public List<Entry> list() {
        return new ArrayList<>(entries.values());
    }

    public Entry get(String id) {
        return id == null ? null : entries.get(id);
    }

    /** 新建声线——只有名字必填,其余可空;持久化并返回。 */
    public Entry create(String name, String backend, String url, String apiKey, String groupId,
                        String model, String voice, String refAudio, String promptText,
                        String textLang, float volume) {
        String id = "voice_" + Long.toHexString(System.currentTimeMillis()) + "_" + entries.size();
        Entry e = new Entry(id, name, backend, url, apiKey, groupId, model, voice,
                refAudio, promptText, textLang, clampVolume(volume));
        entries.put(id, e);
        save();
        return e;
    }

    public void update(Entry e) {
        if (e == null || !entries.containsKey(e.id())) return;
        entries.put(e.id(), new Entry(e.id(), e.name(), e.backend(), e.url(), e.apiKey(),
                e.groupId(), e.model(), e.voice(), e.refAudio(), e.promptText(), e.textLang(),
                clampVolume(e.volume())));
        save();
    }

    /** 删除声线。指向它的绑定保持原样(悬空绑定 {@link #resolve} 得 null = 静音),与模型配置库同语义。 */
    public void remove(String id) {
        if (entries.remove(id) != null) save();
    }

    // ---- per-companion assignment (uuid → entry id) ----

    /** 这个同伴绑定的声线条目 id,或 null(未绑定 / null 同伴)。 */
    public String assignedEntry(UUID companion) {
        return companion == null ? null : assignments.get(companion.toString());
    }

    /** 给同伴绑定声线({@code entryId} null/blank = 解绑静音)并持久化。 */
    public void assign(UUID companion, String entryId) {
        if (companion == null) return;
        if (entryId == null || entryId.isBlank()) {
            assignments.remove(companion.toString());
        } else {
            assignments.put(companion.toString(), entryId);
        }
        save();
    }

    /**
     * 语音管线的唯一入口:这个同伴此刻应该用哪条声线。全局开关关闭、
     * 未绑定、或绑定指向已删除的条目,都返回 null(= 静音)。
     */
    public Entry resolve(UUID companion) {
        if (!enabled || companion == null) return null;
        return get(assignments.get(companion.toString()));
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

    // ---- persistence ----

    private void load() {
        entries.clear();
        assignments.clear();
        enabled = true;   // 缺省开——玩家配好声线就该出声,关闭是显式选择
        if (!Files.exists(file)) {
            return;   // 全新安装:tab 从空开始,玩家自己创建
        }
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
            if (root.has("entries") && root.get("entries").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("entries")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    Entry e = new Entry(str(o, "id"), str(o, "name"), str(o, "backend"),
                            str(o, "url"), str(o, "api_key"), str(o, "group_id"),
                            str(o, "model"), str(o, "voice"),
                            str(o, "ref_audio"), str(o, "prompt_text"), str(o, "text_lang"),
                            clampVolume(o.has("volume") ? o.get("volume").getAsFloat() : 1.0f));
                    if (e.id() != null && !e.id().isBlank()) entries.put(e.id(), e);
                }
            }
            if (root.has("assignments") && root.get("assignments").isJsonObject()) {
                for (var kv : root.getAsJsonObject("assignments").entrySet()) {
                    if (kv.getValue().isJsonPrimitive()) {
                        assignments.put(kv.getKey(), kv.getValue().getAsString());
                    }
                }
            }
        } catch (RuntimeException | IOException ex) {
            Constants.LOG.warn("[numen-voice] unreadable {} — starting empty ({})",
                    file, ex.getMessage());
            entries.clear();
            assignments.clear();
            enabled = false;
        }
    }

    private void save() {
        JsonArray arr = new JsonArray();
        for (Entry e : entries.values()) {
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
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("enabled", enabled);
        root.add("entries", arr);
        JsonObject assign = new JsonObject();
        assignments.forEach(assign::addProperty);
        root.add("assignments", assign);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, PRETTY.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-voice] can't save {}: {}", file, ex.getMessage());
        }
    }

    /** 音量夹到 0–2(NaN → 1);GUI 表单与加载路径共用。 */
    public static float clampVolume(float v) {
        if (Float.isNaN(v)) return 1.0f;
        return Math.max(0f, Math.min(2f, v));
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static boolean nb(String s) {
        return s != null && !s.isBlank();
    }
}
