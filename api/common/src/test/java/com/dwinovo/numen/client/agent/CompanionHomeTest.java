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
import java.util.Set;
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
        CompanionHome.init(root);
    }

    @AfterEach
    void restore() {
        CompanionHome.init(null);
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

    // ---- 对账(删除本地数据的唯一入口) ----

    @Test
    void reconcileSweepsTheDismissedAndClaimsTheRest() throws IOException {
        Files.writeString(CompanionHome.chat(A), "a\n", StandardCharsets.UTF_8);
        Files.writeString(CompanionHome.chat(B), "b\n", StandardCharsets.UTF_8);
        CompanionHome.claim(A, "world-1");
        CompanionHome.claim(B, "world-1");

        assertEquals(1, CompanionHome.reconcile("world-1", Set.of(A)), "B 不在名册上 = 被遣散了");

        assertTrue(Files.isDirectory(root.resolve("companions").resolve(A.toString())));
        assertFalse(Files.exists(root.resolve("companions").resolve(B.toString())));
        assertEquals("world-1", CompanionHome.world(A), "名册上的同伴顺手认领世界归属");
    }

    @Test
    void reconcileNeverTouchesAnotherWorldsCompanions() throws IOException {
        // 换个存档进去:上一个存档的同伴当然不在这份名册上,但她们的数据一根汗毛都不能动
        Files.writeString(CompanionHome.chat(A), "存档一的会话\n", StandardCharsets.UTF_8);
        CompanionHome.claim(A, "world-1");
        Files.writeString(CompanionHome.chat(B), "存档二的会话\n", StandardCharsets.UTF_8);
        CompanionHome.claim(B, "world-2");

        assertEquals(0, CompanionHome.reconcile("world-2", Set.of(B)));

        assertEquals("存档一的会话\n", Files.readString(CompanionHome.chat(A), StandardCharsets.UTF_8));
        assertEquals("world-1", CompanionHome.world(A), "别人的世界标记不许被改写");
    }

    @Test
    void reconcileLeavesUnclaimedHomesAlone() throws IOException {
        // 旧版本迁移过来的数据没标世界,归属不明——宁可留孤儿也不删错
        Files.writeString(CompanionHome.chat(A), "来历不明\n", StandardCharsets.UTF_8);
        assertNull(CompanionHome.world(A));

        assertEquals(0, CompanionHome.reconcile("world-1", Set.of()));
        assertTrue(Files.exists(CompanionHome.chat(A)));
    }

    @Test
    void reconcileWithoutAWorldIdIsANoOp() throws IOException {
        // 还没收到过名册(或服务端是老版本)就不知道自己在哪儿,一个都不能删
        Files.writeString(CompanionHome.chat(A), "a\n", StandardCharsets.UTF_8);
        CompanionHome.claim(A, "world-1");

        assertEquals(0, CompanionHome.reconcile(null, Set.of()));
        assertEquals(0, CompanionHome.reconcile("", Set.of()));
        assertTrue(Files.exists(CompanionHome.chat(A)));
    }

    @Test
    void claimIsIdempotentAndRejectsBlanks() {
        CompanionHome.claim(A, "world-1");
        CompanionHome.claim(A, "world-1");
        assertEquals("world-1", CompanionHome.world(A));

        CompanionHome.claim(B, null);
        CompanionHome.claim(B, "   ");
        assertNull(CompanionHome.world(B), "空白不是世界");
    }

    @Test
    void sweptHomeTakesTheWorldMarkerWithIt() {
        CompanionHome.bind(A, CompanionHome.Binding.EMPTY.withProvider("p"));
        CompanionHome.claim(A, "world-1");

        CompanionHome.reconcile("world-1", Set.of());

        assertNull(CompanionHome.world(A), "整个目录都没了,标记自然也没了");
        assertTrue(CompanionHome.binding(A).isEmpty());
        assertTrue(CompanionHome.known().isEmpty());
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
        Files.writeString(conv.resolve(A + ".jsonl.v1.bak"), "old-format\n", StandardCharsets.UTF_8);
        Files.writeString(mem.resolve(A + ".blocks.json"), "{\"b\":1}", StandardCharsets.UTF_8);

        CompanionHome.migrateLegacy();

        assertEquals("chat-a\n", Files.readString(CompanionHome.chat(A), StandardCharsets.UTF_8));
        assertEquals("{\"t\":1}", Files.readString(CompanionHome.stats(A), StandardCharsets.UTF_8));
        assertEquals("inbox-a\n", Files.readString(CompanionHome.inbox(A), StandardCharsets.UTF_8));
        assertEquals("{\"b\":1}", Files.readString(CompanionHome.blocks(A), StandardCharsets.UTF_8));
        assertEquals("old-format\n", Files.readString(
                CompanionHome.dir(A).resolve("chat.jsonl.v1.bak"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(conv.resolve(A + ".jsonl")), "搬走就不该留在原地");
        // 一个没搬走的文件就会让旧目录永远删不掉,迁移于是年年重跑——所以这条必须验
        assertFalse(Files.isDirectory(conv), "搬空的旧目录随手删掉 = 迁移完成的标记");
        assertFalse(Files.isDirectory(mem));
    }

    @Test
    void migrationClaimsPersonaBindingFromTheLog() throws IOException {
        // 旧版把人设绑定记在会话日志的事件里(事件溯源,后写胜出)
        Path conv = Files.createDirectories(root.resolve("conversations"));
        Files.writeString(conv.resolve(A + ".jsonl"), """
                {"type":"header","v":2}
                {"role":"user","content":"在吗"}
                {"type":"persona-change","id":"旧人设","content":"正文","name":"旧"}
                {"type":"persona-change","id":"新人设","content":"正文2","name":"新"}
                """, StandardCharsets.UTF_8);

        CompanionHome.migrateLegacy();

        assertEquals("新人设", CompanionHome.binding(A).personaId(), "认最后一条,不是第一条");
    }

    @Test
    void personaClaimNeverOverwritesAnExistingBinding() throws IOException {
        CompanionHome.bind(A, CompanionHome.Binding.EMPTY.withPersona("主人刚选的"));
        Path conv = Files.createDirectories(root.resolve("conversations"));
        Files.writeString(conv.resolve(A + ".jsonl"),
                "{\"type\":\"persona-change\",\"id\":\"日志里的老货\"}\n", StandardCharsets.UTF_8);

        CompanionHome.migrateLegacy();

        assertEquals("主人刚选的", CompanionHome.binding(A).personaId());
    }

    @Test
    void migrationStopsRunningOnceTheOldLayoutIsGone() throws IOException {
        Files.createDirectories(root.resolve("conversations"));
        CompanionHome.migrateLegacy();          // 空目录也算搬完:删掉
        assertFalse(Files.isDirectory(root.resolve("conversations")));

        // 之后新写的分隔不带 id,就算再跑也认领不出东西——但根本不会再跑
        CompanionHome.bind(A, CompanionHome.Binding.EMPTY.withProvider("p"));
        Files.writeString(CompanionHome.chat(A),
                "{\"type\":\"persona-change\",\"ts\":1}\n", StandardCharsets.UTF_8);
        CompanionHome.migrateLegacy();
        assertNull(CompanionHome.binding(A).personaId());
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
        Files.createDirectories(conv);
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
