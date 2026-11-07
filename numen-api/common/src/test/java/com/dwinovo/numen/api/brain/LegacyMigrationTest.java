package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 搬家只做一次、可重来、不丢:复制不移动,目标要么不在要么完整,崩在半路的残留下次清掉重来。 */
class LegacyMigrationTest {

    private static final UUID HER = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private static JsonDoc doc(Path file) {
        return new JsonDoc(file, JsonDoc.Format.of(1), Io.direct(e -> { }));
    }

    private static void text(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** 一份旧的家:日志与它的备份、收件箱、台账、目标、札记、世界标记、没人再读的 blocks.json,以及四项的绑定。 */
    private Path legacyHome(Path root) throws IOException {
        Path home = root.resolve("companions").resolve(HER.toString());
        text(home.resolve("chat.jsonl"), "{\"type\":\"header\",\"v\":4}\n{\"type\":\"message\",\"id\":\"a1\"}\n");
        text(home.resolve("chat.jsonl.v3.bak"), "old");
        text(home.resolve("inbox.jsonl"), "queued");
        text(home.resolve("stats.json"), "{\"totalTokens\":7}");
        text(home.resolve("goal.json"), "{\"objective\":\"挖铁\"}");
        text(home.resolve("memory").resolve("base.md"), "---\nname: base\n---\n\n门朝东\n");
        text(home.resolve("world"), "old-world");
        text(home.resolve("blocks.json"), "{}");
        JsonObject binding = new JsonObject();
        binding.addProperty("provider", "prov1");
        binding.addProperty("persona", "小焰");
        binding.addProperty("voice", "voice1");
        binding.addProperty("skin", "skin1");
        doc(home.resolve("binding.json")).write(binding);
        return home;
    }

    @Test
    void herHomeIsCopiedWholeAndTheLegacyOneStaysWhereItWas(@TempDir Path dir) throws IOException {
        Path legacy = legacyHome(dir.resolve("config"));
        Path world = dir.resolve("save").resolve("companions").resolve(HER.toString());

        assertTrue(LegacyMigration.companion(legacy, world));

        for (String file : List.of("chat.jsonl", "chat.jsonl.v3.bak", "inbox.jsonl", "stats.json", "goal.json",
                "memory/base.md")) {
            assertEquals(Files.readString(legacy.resolve(file)), Files.readString(world.resolve(file)), file);
        }
        assertFalse(Files.exists(world.resolve("world")), "世界自己就是归属");
        assertFalse(Files.exists(world.resolve("blocks.json")), "没人再读的不搬");
        assertTrue(Files.exists(legacy.resolve("chat.jsonl")) && Files.exists(legacy.resolve("blocks.json")), "旧的不动");
        assertFalse(Files.exists(world.resolveSibling(HER + ".migrating")), "暂存目录不留");
    }

    @Test
    void onlyTheBindingsTheLoopReadsGoToTheWorldAndTheClientKeepsItsOwn(@TempDir Path dir) throws IOException {
        Path legacy = legacyHome(dir.resolve("config"));
        Path world = dir.resolve("save").resolve("companions").resolve(HER.toString());

        LegacyMigration.companion(legacy, world);

        JsonObject moved = doc(world.resolve("binding.json")).read().orElseThrow();
        assertEquals("prov1", moved.get("provider").getAsString());
        assertEquals("小焰", moved.get("persona").getAsString());
        assertFalse(moved.has("voice") || moved.has("skin"), "声线与皮肤还在客户端");
        JsonObject kept = doc(legacy.resolve("binding.json")).read().orElseThrow();
        assertEquals("voice1", kept.get("voice").getAsString(), "旧文件里客户端要读的两项原样在");
    }

    @Test
    void aBindingWithNothingTheLoopReadsIsNotCarriedOver(@TempDir Path dir) throws IOException {
        Path legacy = legacyHome(dir.resolve("config"));
        JsonObject onlyLooks = new JsonObject();
        onlyLooks.addProperty("voice", "voice1");
        doc(legacy.resolve("binding.json")).write(onlyLooks);
        Path world = dir.resolve("save").resolve("companions").resolve(HER.toString());

        LegacyMigration.companion(legacy, world);

        assertFalse(Files.exists(world.resolve("binding.json")));
    }

    @Test
    void doingItTwiceChangesNothing(@TempDir Path dir) throws IOException {
        Path legacy = legacyHome(dir.resolve("config"));
        Path world = dir.resolve("save").resolve("companions").resolve(HER.toString());
        LegacyMigration.companion(legacy, world);
        text(world.resolve("chat.jsonl"), "她在世界里又说了话");

        assertFalse(LegacyMigration.companion(legacy, world), "目标在了就是迁过了");

        assertEquals("她在世界里又说了话", Files.readString(world.resolve("chat.jsonl")), "世界里新写的不被旧的盖掉");
    }

    @Test
    void whatACrashLeftHalfDoneIsClearedAndTheNextRunIsWhole(@TempDir Path dir) throws IOException {
        Path legacy = legacyHome(dir.resolve("config"));
        Path world = dir.resolve("save").resolve("companions").resolve(HER.toString());
        text(world.resolveSibling(HER + ".migrating").resolve("chat.jsonl"), "半截");
        text(world.resolveSibling(HER + ".migrating").resolve("stray.txt"), "上次的残留");

        assertTrue(LegacyMigration.companion(legacy, world));

        assertEquals(Files.readString(legacy.resolve("chat.jsonl")), Files.readString(world.resolve("chat.jsonl")));
        assertFalse(Files.exists(world.resolve("stray.txt")), "半成品不混进完整的");
    }

    @Test
    void aCompanionWithNoLegacyHomeIsLeftAlone(@TempDir Path dir) {
        Path world = dir.resolve("save").resolve("companions").resolve(HER.toString());

        assertFalse(LegacyMigration.companion(dir.resolve("config").resolve("companions").resolve(HER.toString()), world));
        assertFalse(Files.exists(world), "没有旧的就不凭空建一个空家");
    }

    @Test
    void aFileIsCopiedOnceAndNeverOverwritten(@TempDir Path dir) throws IOException {
        text(dir.resolve("old").resolve("ui.json"), "旧的");

        assertTrue(LegacyMigration.file(dir.resolve("old").resolve("ui.json"), dir.resolve("client").resolve("ui.json")));
        text(dir.resolve("client").resolve("ui.json"), "新的");

        assertFalse(LegacyMigration.file(dir.resolve("old").resolve("ui.json"), dir.resolve("client").resolve("ui.json")));
        assertEquals("新的", Files.readString(dir.resolve("client").resolve("ui.json")));
        assertEquals("旧的", Files.readString(dir.resolve("old").resolve("ui.json")), "旧的原样留着");
        assertFalse(Files.exists(dir.resolve("client").resolve("ui.json.migrating")), "暂存不留");
    }

    @Test
    void aTreeIsCopiedWholeOnceAndItsHalfDoneLeftoverIsCleared(@TempDir Path dir) throws IOException {
        text(dir.resolve("old").resolve("skins").resolve("a.png"), "png");
        text(dir.resolve("client").resolve("skins.migrating").resolve("stray"), "上次的残留");

        assertTrue(LegacyMigration.tree(dir.resolve("old").resolve("skins"), dir.resolve("client").resolve("skins")));

        assertEquals("png", Files.readString(dir.resolve("client").resolve("skins").resolve("a.png")));
        assertFalse(Files.exists(dir.resolve("client").resolve("skins").resolve("stray")));
        assertFalse(LegacyMigration.tree(dir.resolve("old").resolve("skins"), dir.resolve("client").resolve("skins")));
    }

    @Test
    void onlyTheConversationsOfThisWorldAreCarried(@TempDir Path dir) {
        Conversation mine = new Conversation("g1", "挖矿队", List.of(A, B), 3);
        Conversation elsewhere = new Conversation("g2", "别的世界", List.of(UUID.randomUUID(), UUID.randomUUID()), 1);
        JsonArray entries = new JsonArray();
        entries.add(mine.toJson());
        entries.add(elsewhere.toJson());
        JsonObject legacy = new JsonObject();
        legacy.add("entries", entries);
        legacy.addProperty("selected", "g1");
        Path legacyFile = dir.resolve("config").resolve("conversations.json");
        doc(legacyFile).write(legacy);
        Path worldFile = dir.resolve("save").resolve("players").resolve("owner").resolve("conversations.json");

        assertTrue(LegacyMigration.conversations(legacyFile, c -> c.has(A), worldFile));

        JsonObject written = doc(worldFile).read().orElseThrow();
        assertEquals(1, written.getAsJsonArray("entries").size());
        assertEquals(mine, Conversation.fromJson(written.getAsJsonArray("entries").get(0).getAsJsonObject()));
        assertFalse(written.has("selected"), "选中是客户端的展示偏好");
        assertFalse(LegacyMigration.conversations(legacyFile, c -> c.has(A), worldFile), "已经有就不再写");
    }

    @Test
    void theOldInitiativeMovesToTheOwnersPrefsOnTheServerOnce(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("config");
        text(config.resolve("ui.json"), "{\"v\":1,\"theme\":\"light\",\"initiative\":7}");
        Path prefs = dir.resolve("server").resolve("players").resolve("owner").resolve("prefs.json");

        assertTrue(LegacyMigration.initiative(config, prefs));

        assertEquals(7, doc(prefs).read().orElseThrow().get("initiative").getAsInt());
        assertFalse(LegacyMigration.initiative(config, prefs), "已经有就不再写");
    }

    @Test
    void anOldUiFileWithoutInitiativeCarriesNothing(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("config");
        text(config.resolve("ui.json"), "{\"v\":1,\"theme\":\"light\"}");
        Path prefs = dir.resolve("server").resolve("prefs.json");

        assertFalse(LegacyMigration.initiative(config, prefs));
        assertFalse(Files.exists(prefs));
    }
}
