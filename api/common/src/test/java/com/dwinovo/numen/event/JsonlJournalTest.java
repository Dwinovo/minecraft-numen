package com.dwinovo.numen.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队列落盘的实际磁盘格式。
 *
 * <p>它扛的是"关游戏时没消费完的输入,下次登录还在"——同伴不失忆全靠这一层。
 * 队列本身的规则用内存 journal 测,但<b>真正写盘这条路必须单独测</b>:
 * 落盘丢了 {@code urgent} 或 {@code ts},表现是她把三小时前的急事当成刚发生的
 * 普通事,而那种错在内存测试里一辈子看不出来。
 */
class JsonlJournalTest {

    private static final long T0 = 1_700_000_000_000L;

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("nested").resolve("inbox.jsonl");
    }

    @Test
    void everyFieldSurvivesTheRoundTrip() {
        JsonlJournal journal = JsonlJournal.atFile(file());
        journal.save(List.of(
                new EventQueue.Entry(EventTypes.EVENT, "<event>任务失败了</event>", T0, true),
                new EventQueue.Entry(EventTypes.QUERY, "<query>在吗</query>", T0 + 5, false)));

        List<EventQueue.Entry> back = journal.load();

        assertEquals(2, back.size());
        assertEquals(EventTypes.EVENT, back.get(0).type());
        assertEquals("<event>任务失败了</event>", back.get(0).text());
        assertEquals(T0, back.get(0).ts(), "事发时刻丢了,年龄标注就全错");
        assertTrue(back.get(0).urgent(), "急件丢了,重进游戏她就不会主动开口");
        assertFalse(back.get(1).urgent());
        assertEquals(EventTypes.QUERY, back.get(1).type());
    }

    @Test
    void orderIsPreserved() {
        JsonlJournal journal = JsonlJournal.atFile(file());
        journal.save(List.of(
                new EventQueue.Entry(EventTypes.EVENT, "一", T0, false),
                new EventQueue.Entry(EventTypes.EVENT, "二", T0, false),
                new EventQueue.Entry(EventTypes.EVENT, "三", T0, false)));

        assertEquals(List.of("一", "二", "三"),
                journal.load().stream().map(EventQueue.Entry::text).toList());
    }

    @Test
    void savingEmptyRemovesTheFile() {
        JsonlJournal journal = JsonlJournal.atFile(file());
        journal.save(List.of(new EventQueue.Entry(EventTypes.QUERY, "喂", T0, true)));
        assertTrue(Files.exists(file()));

        journal.save(List.of());

        assertFalse(Files.exists(file()), "空箱不留空文件——否则下次开机读出一个空壳");
        assertTrue(journal.load().isEmpty());
    }

    @Test
    void missingFileReadsAsEmptyNotAsAnError() {
        assertTrue(JsonlJournal.atFile(dir.resolve("从来没写过.jsonl")).load().isEmpty());
    }

    @Test
    void aCorruptLineIsSkippedAndTheRestSurvives() {
        // 输入队列出问题不该让对话停摆:坏一行就丢一行,别的照读
        JsonlJournal journal = JsonlJournal.atFile(file());
        journal.save(List.of(new EventQueue.Entry(EventTypes.EVENT, "占位", T0, false)));
        writeRaw("{ 这行坏了\n"
                + "{\"type\":\"query\",\"text\":\"这行好的\",\"ts\":" + T0 + "}\n"
                + "\n"
                + "{\"type\":\"event\",\"text\":\"这行也好\",\"ts\":0}\n");

        List<EventQueue.Entry> back = journal.load();

        assertEquals(2, back.size());
        assertEquals("这行好的", back.get(0).text());
        assertEquals("这行也好", back.get(1).text());
    }

    @Test
    void missingOptionalFieldsFallBackInsteadOfThrowing() {
        JsonlJournal journal = JsonlJournal.atFile(file());
        journal.save(List.of(new EventQueue.Entry(EventTypes.EVENT, "占位", T0, false)));
        writeRaw("{\"type\":\"event\",\"text\":\"没有 ts 也没有 urgent\"}\n");

        List<EventQueue.Entry> back = journal.load();

        assertEquals(1, back.size());
        assertEquals(0L, back.get(0).ts());
        assertFalse(back.get(0).urgent());
    }

    @Test
    void aQueueBackedByDiskReloadsWithEverythingIntact() {
        // 端到端:队列 → 磁盘 → 新队列,急件与年龄都还在
        JsonlJournal journal = JsonlJournal.atFile(file());
        EventQueue q = new EventQueue(journal);
        q.push(EventTypes.EVENT, "<event>矿挖完了</event>", T0, true);
        q.push(EventTypes.QUERY, "<query>辛苦了</query>", T0, true);

        EventQueue reopened = new EventQueue(journal);

        assertEquals(2, reopened.size());
        assertTrue(reopened.hasUrgent(), "重进游戏它还是急的");
        assertEquals(1, reopened.count(EventTypes.QUERY));
        assertTrue(reopened.drain(T0 + 3 * 3600_000L).get(0).startsWith("[发生于约3小时前]"),
                "时间戳过了磁盘一圈还得能算出年龄");
    }

    @Test
    void drainingThroughDiskLeavesNothingBehind() {
        JsonlJournal journal = JsonlJournal.atFile(file());
        EventQueue q = new EventQueue(journal);
        q.push(EventTypes.QUERY, "<query>喂</query>", T0, true);

        q.drain(T0);

        assertFalse(Files.exists(file()));
        assertTrue(new EventQueue(journal).isEmpty(), "消费过的输入不该再冒出来");
    }

    private void writeRaw(String content) {
        try {
            Files.writeString(file(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
