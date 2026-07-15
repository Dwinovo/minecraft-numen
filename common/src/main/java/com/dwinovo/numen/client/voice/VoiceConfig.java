package com.dwinovo.numen.client.voice;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 语音配置：{@code config/numen/voice.json}，手工编辑（本轮不做 GUI）。
 * 文件不存在或 {@code enabled:false} 时整条语音管线零开销——不建对象、不发请求。
 * 文件按修改时间热重载：改完存盘,下一轮对话即生效,无需重启游戏。
 *
 * <h2>文件样例</h2>
 * <pre>{@code
 * {
 *   "enabled": true,
 *   "companions": {
 *     "8f36c7f0-1c4e-4a3b-9d2e-0a1b2c3d4e5f": {
 *       "backend": "openai",
 *       "url": "https://api.siliconflow.cn",
 *       "apiKey": "sk-xxxx",
 *       "model": "FunAudioLLM/CosyVoice2-0.5B",
 *       "voice": "FunAudioLLM/CosyVoice2-0.5B:alex",
 *       "volume": 1.0
 *     },
 *     "0d9e4c2a-7b65-4321-8888-1234567890ab": {
 *       "backend": "gpt_sovits",
 *       "url": "http://127.0.0.1:9880",
 *       "refAudio": "D:/voices/paimon_ref.wav",
 *       "promptText": "参考音频里说的那句话",
 *       "textLang": "zh",
 *       "volume": 1.2
 *     }
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code enabled} — 全局开关,缺省 false;</li>
 *   <li>{@code companions} — 同伴 UUID（聊天面板/日志里的 entity_uuid）→ 语音条目。
 *       没有条目的同伴保持静音;</li>
 *   <li>{@code backend} — {@code "openai"}（OpenAI /v1/audio/speech 协议,含硅基流动等）
 *       或 {@code "gpt_sovits"}（api_v2 的 /tts）;</li>
 *   <li>{@code volume} — 0.0–2.0,缺省 1.0（>1 会扩大可听半径,响度上限仍是 1）。</li>
 * </ul>
 */
public final class VoiceConfig {

    /** 单个同伴的语音条目（{@code companions} 的一个值）。 */
    public record CompanionVoice(String backend, String url, String apiKey,
                                 String model, String voice,
                                 String refAudio, String promptText, String textLang,
                                 float volume) {

        /** 据 backend 字段实例化对应 TTS 实现。 */
        public TtsBackend createBackend() {
            if ("gpt_sovits".equalsIgnoreCase(backend)) {
                return new GptSovitsTts(url, refAudio, promptText, textLang);
            }
            return new OpenAiCompatibleTts(url, apiKey, model, voice);
        }
    }

    private record Snapshot(long mtime, boolean enabled, Map<UUID, CompanionVoice> companions) {}

    private static final Snapshot EMPTY = new Snapshot(-1, false, Map.of());
    private static volatile Snapshot cache = EMPTY;

    private VoiceConfig() {}

    /**
     * 这个同伴当前的语音条目;全局关闭 / 文件缺失 / 没配这个 UUID 都返回 null
     * （= 该同伴静音）。每次调用做一次 mtime 检查,文件变更即热重载。
     */
    public static CompanionVoice forCompanion(UUID uuid) {
        Snapshot s = current();
        if (!s.enabled()) return null;
        return s.companions().get(uuid);
    }

    private static Snapshot current() {
        Path file = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("numen").resolve("voice.json");
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (Exception missing) {
            cache = EMPTY;
            return EMPTY;
        }
        Snapshot s = cache;
        if (s.mtime() == mtime) return s;
        s = load(file, mtime);
        cache = s;
        return s;
    }

    private static Snapshot load(Path file, long mtime) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            boolean enabled = root.has("enabled") && root.get("enabled").getAsBoolean();
            Map<UUID, CompanionVoice> map = new java.util.HashMap<>();
            if (root.has("companions") && root.get("companions").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("companions").entrySet()) {
                    if (!e.getValue().isJsonObject()) continue;
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(e.getKey().trim());
                    } catch (IllegalArgumentException bad) {
                        Constants.LOG.warn("[numen-voice] voice.json 里不是合法 UUID 的键被忽略: {}", e.getKey());
                        continue;
                    }
                    map.put(uuid, parseEntry(e.getValue().getAsJsonObject()));
                }
            }
            Constants.LOG.info("[numen-voice] voice.json 已加载: enabled={}, {} 个同伴配置",
                    enabled, map.size());
            return new Snapshot(mtime, enabled, Map.copyOf(map));
        } catch (Exception ex) {
            Constants.LOG.warn("[numen-voice] voice.json 解析失败,语音保持关闭: {}", ex.toString());
            return new Snapshot(mtime, false, Map.of());
        }
    }

    private static CompanionVoice parseEntry(JsonObject o) {
        float volume = o.has("volume") ? o.get("volume").getAsFloat() : 1.0f;
        volume = Math.max(0f, Math.min(2f, volume));
        return new CompanionVoice(
                str(o, "backend"), str(o, "url"), str(o, "apiKey"),
                str(o, "model"), str(o, "voice"),
                str(o, "refAudio"), str(o, "promptText"), str(o, "textLang"),
                volume);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
