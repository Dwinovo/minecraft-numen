package com.dwinovo.numen.client.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同伴的家:绑定读写、遣散即清空、旧布局迁移(幂等)。
 * 这套东西的价值全在"生命周期不用写清理代码",所以测试重点是
 * <b>删一次目录就干净</b>与<b>迁移可重跑</b>。
 */
class CompanionHomeTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path root;

    @BeforeEach
    void useTempRoot() {
        CompanionHome.overrideRoot(root);
    }

    @AfterEach
    void restore() {
        CompanionHome.overrideRoot(null);
    }

    // ---- 绑定 ----

    @Test
    void bindingRoundTripsAndOmitsUnsetFields() throws IOException {
        assertEquals(CompanionHome.Binding.EMPTY, CompanionHome.binding(A));

        CompanionHome.bind(A, CompanionHome.Binding.EMPTY
                .withProvider("prov_1").withVoice("voice_9"));
        CompanionHome.Binding read = CompanionHome.binding(A);
        assertEquals("prov_1", read.providerId());
        assertEquals("voice_9", read.voiceId());
        assertNull(read.personaId(), "没绑人设就该是 null,不是空串");

        // 未设的字段不写进文件——binding.json 打开就是"绑了什么"的完整答案
        JsonObject o = JsonParser.parseString(Files.readString(
                root.resolve("companions").resolve(A.toString()).resolve("binding.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(o.has("provider"));
        assertTrue(o.has("voice"));
        assertFalse(o.has("persona"));
    }

    @Test
    void blankUnbindsAndEmptyBindingRemovesTheFile() {
        CompanionHome.bind(A, CompanionHome.Binding.EMPTY.withPersona("小焰"));
        Path file = root.resolve("companions").resolve(A.toString()).resolve("binding.json");
        assertTrue(Files.isRegularFile(file));

        CompanionHome.bind(A, CompanionHome.binding(A).withPersona("  "));   // 空白 = 解绑
        assertNull(CompanionHome.binding(A).personaId());
        assertFalse(Files.exists(file), "全空的绑定不留空壳文件");
    }

    @Test
    void bindingsAreIsolatedPerCompanion() {
        CompanionHome.bind(A, CompanionHome.Binding.EMPTY.withProvider("prov_a"));
        CompanionHome.bind(B, CompanionHome.Binding.EMPTY.withProvider("prov_b"));
        assertEquals("prov_a", CompanionHome.binding(A).providerId());
        assertEquals("prov_b", CompanionHome.binding(B).providerId());
    }

    // ---- 生命周期 ----

    @Test
    void deleteTakesEverythingAtOnce() throws IOException {
        // 五样数据都建出来
        CompanionHome.bind(A, CompanionHome.Binding.EMPTY.withProvider("prov_1"));
        Files.writeString(CompanionHome.chat(A), "{}\n", StandardCharsets.UTF_8);
        Files.writeString(CompanionHome.stats(A), "{}", StandardCharsets.UTF_8);
        Files.writeString(CompanionHome.inbox(A), "{}\n", StandardCharsets.UTF_8);
        Files.writeString(CompanionHome.blocks(A), "{}", StandardCharsets.UTF_8);
        CompanionHome.bind(B, CompanionHome.Binding.EMPTY.withVoice("voice_1"));

        CompanionHome.delete(A);

        assertFalse(Files.exists(root.resolve("companions").resolve(A.toString())),
                "遣散 = 目录连同五样数据一起走");
        assertEquals("voice_1", CompanionHome.binding(B).voiceId(), "不许殃及别人");
    }

    @Test
    void deleteIsSafeWhenNothingIsThere() {
        CompanionHome.delete(A);   // 没建过家:静默,不抛
        assertTrue(CompanionHome.known().isEmpty());
    }

    @Test
    void knownListsOnlyUuidDirectories() throws IOException {
        CompanionHome.bind(A, CompanionHome.Binding.EMPTY.withProvider("p"));
        CompanionHome.bind(B, CompanionHome.Binding.EMPTY.withProvider("p"));
        Files.createDirectories(root.resolve("companions").resolve("не-uuid"));

        var known = CompanionHome.known();
        assertEquals(2, known.size(), "非 uuid 命名的目录不是我们的东西:" + known);
        assertTrue(known.contains(A));
        assertTrue(known.contains(B));
    }

    // ---- 迁移 ----

    @Test
    void migrationMovesLegacyFilesIntoHomes() throws IOException {
        Path conv = Files.createDirectories(root.resolve("conversations"));
        Path mem = Files.createDirectories(root.resolve("memory"));
        Files.writeString(conv.resolve(A + ".jsonl"), "chat-a\n", StandardCharsets.UTF_8);
        Files.writeString(conv.resolve(A + ".stats.json"), "{\"t\":1}", StandardCharsets.UTF_8);
        Files.writeString(conv.resolve(A + ".inbox.jsonl"), "inbox-a\n", StandardCharsets.UTF_8);
        Files.writeString(mem.resolve(A + ".blocks.json"), "{\"b\":1}", StandardCharsets.UTF_8);

        CompanionHome.migrateLegacy();

        assertEquals("chat-a\n", Files.readString(CompanionHome.chat(A), StandardCharsets.UTF_8));
        assertEquals("{\"t\":1}", Files.readString(CompanionHome.stats(A), StandardCharsets.UTF_8));
        assertEquals("inbox-a\n", Files.readString(CompanionHome.inbox(A), StandardCharsets.UTF_8));
        assertEquals("{\"b\":1}", Files.readString(CompanionHome.blocks(A), StandardCharsets.UTF_8));
        assertFalse(Files.exists(conv.resolve(A + ".jsonl")), "搬走就不该留在原地");
    }

    @Test
    void migrationCollectsAssignmentsAndStripsTheSection() throws IOException {
        Files.writeString(root.resolve("providers.json"),
                "{\"entries\":[],\"assignments\":{\"" + A + "\":\"prov_x\"}}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("voice.json"),
                "{\"entries\":[],\"enabled\":true,\"assignments\":{\"" + A + "\":\"voice_y\"}}",
                StandardCharsets.UTF_8);

        CompanionHome.migrateLegacy();

        CompanionHome.Binding b = CompanionHome.binding(A);
        assertEquals("prov_x", b.providerId());
        assertEquals("voice_y", b.voiceId());

        // 库文件从此只装配置——可以原样分享,里面没有别人看不懂的 UUID
        for (String lib : new String[]{"providers.json", "voice.json"}) {
            JsonObject o = JsonParser.parseString(
                    Files.readString(root.resolve(lib), StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(o.has("assignments"), lib + " 的绑定段应已抹掉");
            assertTrue(o.has("entries"), lib + " 的条目必须原样保留");
        }
        JsonObject voice = JsonParser.parseString(
                Files.readString(root.resolve("voice.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(voice.get("enabled").getAsBoolean(), "其它段(全局开关)不许被迁移误伤");
    }

    @Test
    void migrationIsIdempotentAndKeepsNewerData() throws IOException {
        Path conv = Files.createDirectories(root.resolve("conversations"));
        Files.writeString(conv.resolve(A + ".jsonl"), "old\n", StandardCharsets.UTF_8);
        CompanionHome.migrateLegacy();

        // 第二轮:旧文件又出现(手动还原/多存档),但家里已有更新的内容
        Files.writeString(CompanionHome.chat(A), "new\n", StandardCharsets.UTF_8);
        Files.writeString(conv.resolve(A + ".jsonl"), "old-again\n", StandardCharsets.UTF_8);
        CompanionHome.migrateLegacy();

        assertEquals("new\n", Files.readString(CompanionHome.chat(A), StandardCharsets.UTF_8),
                "已迁过的不许被旧文件盖回去");
    }

    @Test
    void migrationIgnoresForeignFiles() throws IOException {
        Path conv = Files.createDirectories(root.resolve("conversations"));
        Files.writeString(conv.resolve("readme.jsonl"), "x", StandardCharsets.UTF_8);
        Files.writeString(conv.resolve("notes.txt"), "y", StandardCharsets.UTF_8);

        CompanionHome.migrateLegacy();

        assertTrue(Files.exists(conv.resolve("readme.jsonl")), "不是 uuid 命名的文件不碰");
        assertTrue(Files.exists(conv.resolve("notes.txt")));
        assertTrue(CompanionHome.known().isEmpty(), "不该凭空造出同伴的家");
    }

    @Test
    void migrationSurvivesCorruptLibraryFile() throws IOException {
        Files.writeString(root.resolve("providers.json"), "{ broken json", StandardCharsets.UTF_8);
        CompanionHome.migrateLegacy();   // 只记日志,不阻断启动
        assertTrue(CompanionHome.binding(A).isEmpty());
    }
}
