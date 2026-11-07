package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.skin.SkinCache;
import com.dwinovo.numen.api.skin.SkinRef;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.dwinovo.numen.api.voice.VoiceProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 旧的声线库、皮肤库、looks.json 搬进新布局:复制不移动(旧皮肤的 skin.json 搬完改名)、只做一次、key 进本机钥匙串、同伴的选择改写成新名字。 */
class LegacyLooksTest {

    private static final UUID HER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static void write(Path file, JsonObject root) {
        new JsonDoc(file, JsonDoc.Format.of(1), Io.direct(e -> {
            throw e;
        })).write(root);
    }

    private static JsonObject voiceEntry(String id, String name, String backend, String key) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("backend", backend);
        o.addProperty("api_key", key);
        o.addProperty("voice", "alex");
        o.addProperty("volume", 1.2);
        return o;
    }

    private static JsonObject library(JsonObject... entries) {
        JsonArray all = new JsonArray();
        for (JsonObject e : entries) {
            all.add(e);
        }
        JsonObject root = new JsonObject();
        root.add("entries", all);
        return root;
    }

    @Test
    void oldVoicesBecomeMyVoicesAndTheirKeysGoToThisComputersKeychain(@TempDir Path dir) throws IOException {
        Path old = dir.resolve("client").resolve("voice.json");
        write(old, library(voiceEntry("voice_1", "派蒙", "openai", "sk-first"),
                voiceEntry("voice_2", "派蒙", "openai", "sk-second"), voiceEntry("voice_3", "小焰", "doubao", "")));
        Path mine = dir.resolve("players").resolve("host").resolve("voice").resolve("my");
        Path keys = dir.resolve("client").resolve("keys");

        Map<String, String> ids = LegacyLooks.voices(old, mine, keys);

        assertEquals(Map.of("voice_1", "my:派蒙", "voice_2", "my:派蒙_2", "voice_3", "my:小焰"), ids);
        VoiceProfile migrated = VoiceProfile.read(Files.readString(mine.resolve("派蒙.json")));
        assertEquals("openai", migrated.backend());
        assertEquals("alex", migrated.voice());
        assertEquals(1.2f, migrated.volume());
        assertFalse(Files.readString(mine.resolve("派蒙.json")).contains("sk-first"), "条目里没有 key");
        assertEquals("sk-first", Files.readString(keys.resolve("openai.key")), "同一个服务几把 key 留第一把");
        assertFalse(Files.exists(keys.resolve("doubao.key")), "空 key 不存");
        assertTrue(Files.exists(old), "复制不移动");

        Files.writeString(mine.resolve("派蒙.json"), "改过的");
        assertEquals(ids, LegacyLooks.voices(old, mine, keys), "再来一次对照表一样");
        assertEquals("改过的", Files.readString(mine.resolve("派蒙.json")), "只做一次,不覆盖");
    }

    @Test
    void oldSkinsBecomePngsInTheFolderAndSignedOnesGoIntoTheCache(@TempDir Path dir) throws IOException {
        Path old = dir.resolve("client").resolve("skin.json");
        JsonObject signed = new JsonObject();
        signed.addProperty("id", "skin_1");
        signed.addProperty("name", "小红");
        signed.addProperty("variant", "slim");
        signed.addProperty("value", "v");
        signed.addProperty("signature", "s");
        JsonObject unsigned = new JsonObject();
        unsigned.addProperty("id", "skin_2");
        unsigned.addProperty("name", "没签成");
        write(old, library(signed, unsigned));
        Files.createDirectories(dir.resolve("client").resolve("skins"));
        byte[] png = {9, 8, 7};
        Files.write(dir.resolve("client").resolve("skins").resolve("skin_1.png"), png);
        Files.write(dir.resolve("client").resolve("skins").resolve("skin_2.png"), new byte[] {1});
        Path folder = dir.resolve("skins");
        SkinCache cache = new SkinCache(new Home(dir.resolve("server"), Io.direct(e -> {
            throw e;
        })));

        Map<String, String> ids = LegacyLooks.skins(old, dir.resolve("client").resolve("skins"), folder, cache);

        SkinRef ref = SkinRef.of(png, "slim");
        assertEquals(Map.of("skin_1", ref.toString()), ids, "没签成的没有可穿的引用");
        assertEquals("v", cache.find(ref).value());
        assertEquals(3, Files.readAllBytes(folder.resolve("小红.png")).length);
        assertEquals(1, Files.readAllBytes(folder.resolve("没签成.png")).length, "没签成的图也成了文件夹里的一张");
        assertFalse(Files.exists(old), "搬完旧文件改了名,不再从它冒出东西");
        assertTrue(Files.exists(dir.resolve("client").resolve("skin.json.migrated")));
        assertEquals(Map.of(), LegacyLooks.skins(old, dir.resolve("client").resolve("skins"), folder, cache), "只做一次");
    }

    @Test
    void herChoicesAreWrittenIntoHerBindingOnlyWhereTheBindingHasNone(@TempDir Path dir) throws IOException {
        JsonObject her = new JsonObject();
        her.addProperty("voice", "voice_1");
        her.addProperty("skin", "skin_9");
        JsonObject companions = new JsonObject();
        companions.add(HER.toString(), her);
        JsonObject looks = new JsonObject();
        looks.add("companions", companions);
        Path looksFile = dir.resolve("client").resolve("looks.json");
        write(looksFile, looks);
        Path binding = dir.resolve("world").resolve("binding.json");
        JsonObject existing = new JsonObject();
        existing.addProperty("skin", "my:已经有的");
        existing.addProperty("persona", "my:小焰");
        write(binding, existing);

        int changed = LegacyLooks.bindings(looksFile, dir.resolve("companions"), Map.of(HER, binding),
                Map.of("voice_1", "my:派蒙"), Map.of("skin_9", SkinRef.of(new byte[] {1}, "classic").toString()));

        JsonObject after = new JsonDoc(binding, JsonDoc.Format.of(1), Io.direct(e -> {
            throw e;
        })).read().orElseThrow();
        assertEquals(1, changed);
        assertEquals("my:派蒙", after.get("voice").getAsString());
        assertEquals("my:已经有的", after.get("skin").getAsString(), "她已经有的不动");
        assertEquals("my:小焰", after.get("persona").getAsString());
    }

    @Test
    void whenThereIsNoLooksFileHerOldHomeBindingIsRead(@TempDir Path dir) {
        Path oldHome = dir.resolve("companions").resolve(HER.toString()).resolve("binding.json");
        JsonObject old = new JsonObject();
        old.addProperty("voice", "voice_7");
        write(oldHome, old);
        Path binding = dir.resolve("world").resolve("binding.json");

        int changed = LegacyLooks.bindings(dir.resolve("client").resolve("looks.json"), dir.resolve("companions"),
                Map.of(HER, binding), Map.of("voice_7", "my:旧声线"), Map.of());

        assertEquals(1, changed);
        assertEquals("my:旧声线", new JsonDoc(binding, JsonDoc.Format.of(1), Io.direct(e -> {
            throw e;
        })).read().orElseThrow().get("voice").getAsString());
    }
}
