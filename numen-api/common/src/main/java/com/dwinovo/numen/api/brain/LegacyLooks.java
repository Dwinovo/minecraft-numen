package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.store.Atomic;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.dwinovo.numen.api.voice.VoiceProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 旧版放在这台电脑上的声线库、皮肤库和每只同伴的声线皮肤选择,搬进新布局(单人游戏的房主;多人服不迁、也不上传本机的任何东西)。
 * 规矩同 {@link LegacyLibraries}:<b>复制不移动</b>(旧的原样留着)、<b>只做一次</b>(目标在了就是迁过了)、<b>可重来</b>
 * (先放进暂存再整个改名)。
 *
 * <ul>
 *   <li>{@code client/voice.json} 里的声线,进房主的"我的"({@code voice/my/});声线条目里不再有 key,旧条目里的 key 进这台电脑的
 *       钥匙串({@code client/keys/},按后端标识存;同一个后端几把不同的 key 留第一把,已经有的不动);</li>
 *   <li>{@code client/skin.json} 加原图 {@code client/skins/<id>.png},进房主的"我的"({@code skin/my/}):条目带手臂模型、
 *       Mojang 签名数据和原图,没签成的留着没签名的样子,在皮肤页编辑保存就用存着的原图重签;</li>
 *   <li>每只同伴用哪条声线哪张皮肤:{@code client/looks.json} 里按同伴 uuid 记着;更早的版本记在她旧家的
 *       {@code binding.json} 里。旧 id 换成新名字({@code my:名字}),写进世界里她的绑定。</li>
 * </ul>
 */
public final class LegacyLooks {

    private LegacyLooks() {}

    private static JsonObject read(Path file) {
        return new JsonDoc(file, JsonDoc.Format.of(1), Io.direct(e -> {
            throw e;
        })).read().orElse(new JsonObject());
    }

    /**
     * 旧的声线库 → 房主的"我的"声线,key 进本机钥匙串。
     *
     * @param hostVoices 房主的 {@code voice/my/} 目录
     * @param keysDir    这台电脑的钥匙串目录 {@code client/keys/}
     * @return 旧声线的 id → 新名字({@code my:名字});没有旧文件是空表。目标已经在时也算得出同一张对照表(绑定还要用)
     */
    public static Map<String, String> voices(Path legacyFile, Path hostVoices, Path keysDir) {
        if (!Files.isRegularFile(legacyFile)) {
            return Map.of();
        }
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
        if (read(legacyFile).get("entries") instanceof JsonArray entries) {
            for (JsonElement el : entries) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject entry = el.getAsJsonObject();
                String name = unique(files, EntryId.clean(string(entry, "name"), "voice"));
                VoiceProfile voice = VoiceProfile.fromJson(entry);
                files.put(name, voice.write());
                if (string(entry, "id") != null) {
                    ids.put(string(entry, "id"), EntryId.MINE + ":" + name);
                }
                String key = string(entry, "api_key");
                if (key != null && !key.isBlank()) {
                    keys.putIfAbsent(voice.keyService(), key.strip());
                }
            }
        }
        LegacyMigration.stage(hostVoices, staging -> files.forEach((name, text) ->
                Atomic.write(staging.resolve(name + ".json"), text)));
        keys.forEach((service, key) -> {
            Path file = keysDir.resolve(service + ".key");
            if (EntryId.segment(service) && !Files.exists(file)) {
                Atomic.write(file, key);
            }
        });
        return ids;
    }

    /**
     * 旧的皮肤库 → 房主的"我的"皮肤。
     *
     * @param legacySkins 旧的原图目录 {@code client/skins/}
     * @param hostSkins   房主的 {@code skin/my/} 目录
     * @return 旧皮肤的 id → 新名字
     */
    public static Map<String, String> skins(Path legacyFile, Path legacySkins, Path hostSkins) {
        if (!Files.isRegularFile(legacyFile)) {
            return Map.of();
        }
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, String> files = new LinkedHashMap<>();
        if (read(legacyFile).get("entries") instanceof JsonArray entries) {
            for (JsonElement el : entries) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject entry = el.getAsJsonObject();
                String oldId = string(entry, "id");
                String name = unique(files, EntryId.clean(string(entry, "name"), "skin"));
                byte[] png = null;
                Path image = oldId == null ? null : legacySkins.resolve(oldId + ".png");
                if (image != null && Files.isRegularFile(image)) {
                    try {
                        png = Files.readAllBytes(image);
                    } catch (IOException e) {
                        Constants.LOG.warn("[numen-migrate] 读不了旧皮肤的原图 {}: {}", image, e.toString());
                    }
                }
                files.put(name, new SkinProfile(string(entry, "variant"), string(entry, "value"),
                        string(entry, "signature"), png).write());
                if (oldId != null) {
                    ids.put(oldId, EntryId.MINE + ":" + name);
                }
            }
        }
        LegacyMigration.stage(hostSkins, staging -> files.forEach((name, text) ->
                Atomic.write(staging.resolve(name + ".json"), text)));
        return ids;
    }

    /**
     * 同伴的绑定里补上声线和皮肤(新名字)。旧的选择先看 {@code looks.json},没有再看她旧家的 {@code binding.json};
     * 新绑定里已经有这一项的不动。只在同伴的家刚搬过来的这一次调:之后她的绑定是她自己的,玩家解绑过的不该被旧选择再绑回去。
     *
     * @param looks       {@code client/looks.json}(可以不存在)
     * @param legacyHomes 旧的 {@code companions/} 目录
     * @param bindings    每只同伴在世界里的 {@code binding.json}
     * @return 改了几个文件
     */
    public static int bindings(Path looks, Path legacyHomes, Map<UUID, Path> bindings, Map<String, String> voiceIds,
                               Map<String, String> skinIds) {
        JsonObject chosen = Files.isRegularFile(looks) && read(looks).get("companions") instanceof JsonObject all
                ? all : new JsonObject();
        int changed = 0;
        for (Map.Entry<UUID, Path> each : bindings.entrySet()) {
            String companion = each.getKey().toString();
            JsonObject old = chosen.get(companion) instanceof JsonObject o ? o
                    : Files.isRegularFile(legacyHomes.resolve(companion).resolve("binding.json"))
                            ? read(legacyHomes.resolve(companion).resolve("binding.json")) : new JsonObject();
            JsonDoc doc = new JsonDoc(each.getValue(), JsonDoc.Format.of(1), Io.direct(e -> {
                throw e;
            }));
            JsonObject binding = doc.read().orElse(new JsonObject());
            boolean edited = adopt(binding, Slot.VOICE.wire(), string(old, "voice"), voiceIds);
            edited |= adopt(binding, Slot.SKIN.wire(), string(old, "skin"), skinIds);
            if (edited) {
                doc.write(binding);
                changed++;
            }
        }
        if (changed > 0) {
            Constants.LOG.info("[numen-migrate] {} 只同伴的声线与皮肤选择写进了她的绑定", changed);
        }
        return changed;
    }

    private static boolean adopt(JsonObject binding, String key, String oldId, Map<String, String> ids) {
        if (oldId == null || binding.has(key) || !ids.containsKey(oldId)) {
            return false;
        }
        binding.addProperty(key, ids.get(oldId));
        return true;
    }

    private static String unique(Map<String, String> taken, String base) {
        String name = base;
        for (int i = 2; taken.containsKey(name); i++) {
            name = base + "_" + i;
        }
        return name;
    }

    private static String string(JsonObject o, String key) {
        return o.get(key) instanceof JsonPrimitive p ? p.getAsString() : null;
    }
}
