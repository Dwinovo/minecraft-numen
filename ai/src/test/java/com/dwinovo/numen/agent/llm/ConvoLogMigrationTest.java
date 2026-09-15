package com.dwinovo.numen.agent.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话日志的一次性迁移。
 *
 * <p>旧版把 {@code <current_task>} 块写进了 user 消息;运行期状态现在每次请求现挂,那份旧副本
 * 留在历史里就会跟现挂的那份打架。迁移只跑一次:再跑一遍文件一个字节都不能变,中途崩了下次
 * 能从头再来。
 */
class ConvoLogMigrationTest {

    private static final String V2_HEADER = "{\"type\":\"header\",\"v\":2,\"created\":1}";
    private static final String LEGACY_USER =
            "{\"role\":\"user\",\"content\":\"<current_task>t1 goto 后台进行中</current_task>\\n<query>繼續任務</query>\",\"ts\":2}";
    private static final String OWNER_TAG_USER =
            "{\"role\":\"user\",\"content\":\"<query>explain <current_task>literal</current_task></query>\",\"ts\":3}";

    private static Path write(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("chat.jsonl");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    private static String content(ConvoState.Msg msg) {
        return assertInstanceOf(ConvoState.Msg.User.class, msg).content();
    }

    private static JsonObject header(Path file) throws IOException {
        return JsonParser.parseString(Files.readAllLines(file, StandardCharsets.UTF_8).get(0)).getAsJsonObject();
    }

    @Test
    void stripsThePersistedTaskBlockButKeepsTheOwnersWords(@TempDir Path dir) throws IOException {
        Path file = write(dir, V2_HEADER, LEGACY_USER);
        ConvoLog log = ConvoLog.atFile(file);

        log.migrateIfNeeded();

        assertEquals("<query>繼續任務</query>", content(log.load(100).get(0)));
        assertEquals(ConvoLog.FORMAT_VERSION, header(file).get("v").getAsInt());
        assertTrue(Files.readString(dir.resolve("chat.jsonl.v2.bak"), StandardCharsets.UTF_8)
                .contains("<current_task>"), "改写前留一份原样的备份");
    }

    @Test
    void neverStripsATagTheOwnerTypedInsideQuery(@TempDir Path dir) throws IOException {
        Path file = write(dir, V2_HEADER, OWNER_TAG_USER);
        ConvoLog log = ConvoLog.atFile(file);

        log.migrateIfNeeded();

        assertEquals("<query>explain <current_task>literal</current_task></query>", content(log.load(100).get(0)));
    }

    @Test
    void stripsCompactSummariesAndTheUserMessagesTheyPreserve(@TempDir Path dir) throws IOException {
        Path file = write(dir, V2_HEADER,
                "{\"type\":\"compact\",\"content\":\"<current_task>t1</current_task>\\n[摘要] 她在挖铁\","
                        + "\"preserved\":[" + LEGACY_USER + "]}");
        ConvoLog log = ConvoLog.atFile(file);

        log.migrateIfNeeded();

        List<ConvoState.Msg> history = log.load(100);
        assertEquals("[摘要] 她在挖铁", content(history.get(0)));
        assertEquals("<query>繼續任務</query>", content(history.get(1)));
    }

    @Test
    void runningItAgainChangesNothing(@TempDir Path dir) throws IOException {
        Path file = write(dir, V2_HEADER, LEGACY_USER, OWNER_TAG_USER);
        ConvoLog log = ConvoLog.atFile(file);

        log.migrateIfNeeded();
        byte[] once = Files.readAllBytes(file);
        log.migrateIfNeeded();

        assertEquals(new String(once, StandardCharsets.UTF_8),
                Files.readString(file, StandardCharsets.UTF_8), "迁移过的文件再迁移一次,一个字节都不变");
    }

    @Test
    void aCrashBeforeTheSwapJustRunsAgain(@TempDir Path dir) throws IOException {
        // 上一次迁移写好了备份和半截临时文件,还没来得及换进去就崩了:原文件原封不动
        Path file = write(dir, V2_HEADER, LEGACY_USER);
        Files.writeString(dir.resolve("chat.jsonl.v2.bak"), V2_HEADER + "\n" + LEGACY_USER + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("chat.jsonl.tmp"), "{\"type\":\"hea", StandardCharsets.UTF_8);
        ConvoLog log = ConvoLog.atFile(file);

        log.migrateIfNeeded();

        assertEquals("<query>繼續任務</query>", content(log.load(100).get(0)));
        assertEquals(ConvoLog.FORMAT_VERSION, header(file).get("v").getAsInt());
        assertFalse(Files.exists(dir.resolve("chat.jsonl.tmp")), "临时文件换进去了,不留在旁边");
    }

    @Test
    void aV1FileGoesStraightToTheCurrentVersion(@TempDir Path dir) throws IOException {
        Path file = write(dir,
                "{\"role\":\"user\",\"content\":\"<current_task>t1</current_task>\\n<query>在吗</query>\"}",
                "{\"role\":\"compact\",\"content\":\"[摘要] 老格式\"}",
                "{\"role\":\"user\",\"content\":\"<query>后来的话</query>\"}");
        ConvoLog log = ConvoLog.atFile(file);

        log.migrateIfNeeded();

        JsonObject head = header(file);
        assertEquals("header", head.get("type").getAsString());
        assertEquals(ConvoLog.FORMAT_VERSION, head.get("v").getAsInt());
        assertTrue(Files.exists(dir.resolve("chat.jsonl.v1.bak")));
        List<ConvoState.Msg> history = log.load(100);
        assertEquals(List.of("[摘要] 老格式", "<query>后来的话</query>"),
                history.stream().map(ConvoLogMigrationTest::content).toList(),
                "老式压缩行照旧从摘要重放");
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            assertTrue(JsonParser.parseString(line).getAsJsonObject().has("ts") || line.contains("header"),
                    "v1 → v2 那一步照旧补 ts:" + line);
            assertFalse(line.contains("current_task"), "v2 → v3 那一步同一次做完:" + line);
        }
    }

    @Test
    void aCurrentFileIsLeftAlone(@TempDir Path dir) throws IOException {
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        log.append(new ConvoState.Msg.User("<current_task>这是新版写的原文</current_task>"));
        byte[] before = Files.readAllBytes(log.file());

        log.migrateIfNeeded();

        assertEquals(new String(before, StandardCharsets.UTF_8),
                Files.readString(log.file(), StandardCharsets.UTF_8), "新文件从创建起就是当前版本,不迁移");
        assertFalse(Files.exists(dir.resolve("chat.jsonl.v2.bak")));
    }
}
