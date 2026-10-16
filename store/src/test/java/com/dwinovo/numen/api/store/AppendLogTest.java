package com.dwinovo.numen.api.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppendLogTest {

    private final List<StoreException> errors = new ArrayList<>();

    private AppendLog log(Path file, AppendLog.Format format) {
        return new AppendLog(file, format, Io.direct(errors::add));
    }

    private AppendLog log(Path file) {
        return log(file, AppendLog.Format.of(1));
    }

    private static JsonObject body(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    @Test
    void readBeforePagesBackwardsFromTheEndAcrossChunks(@TempDir Path dir) {
        AppendLog log = log(dir.resolve("a.jsonl"));
        List<String> ids = new ArrayList<>();
        // 每条约 4 KB 的中文,总共远超一次往前读的块,还跨着多字节字符的边界
        String filler = "挖矿的记录".repeat(300);
        for (int i = 0; i < 40; i++) {
            ids.add(log.append("note", body("text", i + filler)).id());
        }

        List<String> newest = idsOf(log.readBefore(null, 5));
        assertEquals(ids.subList(35, 40), newest);

        List<String> older = idsOf(log.readBefore(newest.get(0), 5));
        assertEquals(ids.subList(30, 35), older);

        List<String> everything = new ArrayList<>();
        String cursor = null;
        while (true) {
            List<String> page = idsOf(log.readBefore(cursor, 7));
            if (page.isEmpty()) {
                break;
            }
            everything.addAll(0, page);
            cursor = page.get(0);
        }
        assertEquals(ids, everything);
        assertTrue(log.readBefore(ids.get(0), 5).records().isEmpty());
    }

    @Test
    void readBeforeSkipsBadLinesAndCountsThem(@TempDir Path dir) throws IOException {
        AppendLog log = log(dir.resolve("a.jsonl"));
        String a = log.append("note", body("text", "甲")).id();
        Files.writeString(log.file(), "not json\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        String b = log.append("note", body("text", "乙")).id();

        AppendLog.Loaded page = log.readBefore(null, 10);
        assertEquals(List.of(a, b), idsOf(page));
        assertEquals(1, page.skipped());
    }

    @Test
    void readBeforeAnUnknownIdIsEmptyNotTheLastPage(@TempDir Path dir) {
        AppendLog log = log(dir.resolve("a.jsonl"));
        log.append("note", body("text", "一"));
        assertTrue(log.readBefore("ffffffff", 5).records().isEmpty());
    }

    private static List<String> idsOf(AppendLog.Loaded loaded) {
        return loaded.records().stream().map(AppendLog.Record::id).toList();
    }

    @Test
    void aFreshFileStartsWithAHeaderAndEveryRecordPointsAtTheOneBefore(@TempDir Path dir) throws IOException {
        AppendLog log = log(dir.resolve("a.jsonl"));
        AppendLog.Record first = log.append("note", body("text", "一"));
        AppendLog.Record second = log.append("note", body("text", "二"));

        List<String> written = lines(log.file());
        JsonObject head = JsonParser.parseString(written.get(0)).getAsJsonObject();
        assertEquals("header", head.get("type").getAsString());
        assertEquals(1, head.get("v").getAsInt());
        assertEquals(3, written.size());
        assertNull(first.parentId());
        assertEquals(first.id(), second.parentId(), "parentId 接的是上一条的 id");
        assertEquals(List.of(first.id(), second.id()),
                log.read().records().stream().map(AppendLog.Record::id).toList());
    }

    @Test
    void idsAreUniqueAndSurviveReopening(@TempDir Path dir) {
        Path file = dir.resolve("a.jsonl");
        Set<String> seen = new HashSet<>();
        AppendLog log = log(file);
        for (int i = 0; i < 2000; i++) {
            assertTrue(seen.add(log.append("n", new JsonObject()).id()), "id 重了");
        }
        AppendLog reopened = log(file);
        AppendLog.Record next = reopened.append("n", new JsonObject());
        assertTrue(seen.add(next.id()), "重新打开之后的新 id 也不能和已有的重");
        assertEquals(2001, reopened.read().records().size());
        assertEquals(reopened.read().records().get(1999).id(), reopened.read().records().get(2000).parentId());
    }

    @Test
    void anUnknownTypeIsHandedBackAndStaysInTheFile(@TempDir Path dir) throws IOException {
        AppendLog log = log(dir.resolve("a.jsonl"));
        log.append("note", body("text", "一"));
        log.append("from-the-future", body("x", "y"));
        log.append("note", body("text", "二"));

        List<String> types = log.read().records().stream().map(AppendLog.Record::type).toList();

        assertEquals(List.of("note", "from-the-future", "note"), types);
        assertTrue(lines(log.file()).get(2).contains("from-the-future"), "追加不碰已有的行");
    }

    @Test
    void aBadLineIsSkippedAndCounted(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        AppendLog log = log(file);
        log.append("note", body("text", "好"));
        Files.writeString(file, "{ 这行坏了\n[1,2]\n{\"没有类型\":1}\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        log.append("note", body("text", "也好"));

        AppendLog.Loaded loaded = log(file).read();

        assertEquals(2, loaded.records().size());
        assertEquals(3, loaded.skipped());
    }

    @Test
    void aTornLastLineDoesNotSwallowTheNextRecord(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        AppendLog log = log(file);
        log.append("note", body("text", "好"));
        Files.writeString(file, "{\"type\":\"note\",\"te", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        AppendLog reopened = log(file);
        reopened.append("note", body("text", "撕掉之后的第一条"));

        AppendLog.Loaded loaded = reopened.read();
        assertEquals(1, loaded.skipped());
        assertEquals("撕掉之后的第一条", loaded.records().get(1).string("text"));
    }

    @Test
    void reservedKeysAreNotForTheBody(@TempDir Path dir) {
        AppendLog log = log(dir.resolve("a.jsonl"));
        assertThrows(IllegalArgumentException.class, () -> log.append("n", body("id", "我自己编一个")));
    }

    // ---- 换版本 ----

    private static final AppendLog.Format V2 = AppendLog.Format.of(2).upgrade(1, records -> {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject r : records) {
            JsonObject c = r.deepCopy();
            c.addProperty("type", "note");
            c.addProperty("upgraded", true);
            out.add(c);
        }
        return out;
    });

    private static final String OLD = "{\"text\":\"一\"}\n{\"text\":\"二\"}\n";

    @Test
    void anOlderFileIsUpgradedAsAWholeWithABackup(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, OLD, StandardCharsets.UTF_8);
        AppendLog log = log(file, V2);

        List<AppendLog.Record> records = log.read().records();

        assertEquals(2, records.size());
        assertTrue(records.get(0).json().get("upgraded").getAsBoolean());
        assertNull(records.get(0).parentId());
        assertEquals(records.get(0).id(), records.get(1).parentId(), "升级后补齐了 id 与 parentId");
        assertEquals(2, JsonParser.parseString(lines(file).get(0)).getAsJsonObject().get("v").getAsInt());
        assertEquals(OLD, Files.readString(dir.resolve("a.jsonl.v1.bak"), StandardCharsets.UTF_8), "替换前留的备份是原文");
        assertFalse(Files.exists(dir.resolve("a.jsonl.tmp")));
    }

    @Test
    void upgradingAgainChangesNothing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, OLD, StandardCharsets.UTF_8);
        log(file, V2).open();
        String once = Files.readString(file, StandardCharsets.UTF_8);

        log(file, V2).open();

        assertEquals(once, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void anUpgradeThatThrowsLeavesTheOriginalUntouched(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, OLD, StandardCharsets.UTF_8);
        AppendLog broken = log(file, AppendLog.Format.of(2).upgrade(1, records -> {
            throw new IllegalStateException("升到一半出错了");
        }));

        assertThrows(StoreException.class, broken::read);

        assertEquals(OLD, Files.readString(file, StandardCharsets.UTF_8));
        assertFalse(Files.exists(dir.resolve("a.jsonl.v1.bak")), "升级没成就不动磁盘");
        assertEquals(2, log(file, V2).read().records().size(), "下次打开从头再来");
    }

    @Test
    void aFailedSwapLeavesTheOriginalUntouched(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, OLD, StandardCharsets.UTF_8);
        Files.createDirectory(dir.resolve("a.jsonl.tmp"));   // 临时文件的位置被占住:写不进去
        AppendLog log = log(file, V2);

        assertThrows(StoreException.class, log::read);

        assertEquals(OLD, Files.readString(file, StandardCharsets.UTF_8), "没换进去,原文件一个字节没动");
        assertEquals(2, log(file, V2).read().records().size(), "失败时占位的临时路径被清掉,下次重来成功");
    }

    @Test
    void aStaleTempFileFromACrashIsReplaced(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, OLD, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("a.jsonl.tmp"), "{\"type\":\"hea", StandardCharsets.UTF_8);

        assertEquals(2, log(file, V2).read().records().size());
        assertFalse(Files.exists(dir.resolve("a.jsonl.tmp")));
    }

    @Test
    void unknownTypesSurviveAnUpgrade(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, "{\"type\":\"header\",\"v\":1,\"created\":1}\n"
                + "{\"type\":\"from-the-future\",\"x\":1}\n", StandardCharsets.UTF_8);
        AppendLog log = log(file, AppendLog.Format.of(2).upgrade(1, records -> records));

        assertEquals(List.of("from-the-future"), log.read().records().stream().map(AppendLog.Record::type).toList());
    }

    @Test
    void aNewerFileIsReadAndAppendedToWithoutBeingRewritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        String newer = "{\"type\":\"header\",\"v\":9,\"created\":1}\n{\"type\":\"note\",\"id\":\"aaaaaaaa\",\"parentId\":null}\n";
        Files.writeString(file, newer, StandardCharsets.UTF_8);
        AppendLog log = log(file, V2);

        AppendLog.Record next = log.append("note", new JsonObject());

        assertEquals("aaaaaaaa", next.parentId());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).startsWith(newer));
        assertFalse(Files.exists(dir.resolve("a.jsonl.v9.bak")));
    }

    @Test
    void aLogThatCannotBeOpenedIsNotWrittenTo(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, OLD, StandardCharsets.UTF_8);
        Files.createDirectory(dir.resolve("a.jsonl.tmp"));
        AppendLog log = log(file, V2);

        AppendLog.Record shown = log.append("note", body("text", "新的"));

        assertEquals("新的", shown.string("text"), "记录照样交回,调用方这一局还看得见");
        assertEquals(OLD, Files.readString(file, StandardCharsets.UTF_8), "没升级的文件里不混进新记录");
        assertFalse(errors.isEmpty(), "原因交给 onError");
    }

    // ---- 整份重写 ----

    @Test
    void rewriteReplacesTheWholeFileAndAnEmptyListDeletesIt(@TempDir Path dir) {
        AppendLog log = log(dir.resolve("a.jsonl"));
        log.append("n", body("text", "一"));

        JsonObject keep = body("text", "留下");
        keep.addProperty("type", "n");
        log.rewrite(List.of(keep));
        List<AppendLog.Record> after = log.read().records();
        assertEquals(1, after.size());
        assertEquals("留下", after.get(0).string("text"));
        assertNull(after.get(0).parentId());

        log.rewrite(List.of());
        assertFalse(Files.exists(log.file()), "空了不留空壳");
        assertTrue(log.read().records().isEmpty());
        AppendLog.Record fresh = log.append("n", new JsonObject());
        assertNull(fresh.parentId(), "清空之后重新开头");
    }

    // ---- 线程 ----

    @Test
    void writesGoThroughTheInjectedExecutorInOrder(@TempDir Path dir) throws Exception {
        ExecutorService disk = Executors.newSingleThreadExecutor();
        try {
            Set<String> threads = new HashSet<>();
            Io io = new Io(job -> disk.execute(() -> {
                threads.add(Thread.currentThread().getName());
                job.run();
            }), errors::add);
            AppendLog log = new AppendLog(dir.resolve("a.jsonl"), AppendLog.Format.of(1), io);
            for (int i = 0; i < 50; i++) {
                log.append("n", body("i", String.valueOf(i)));
            }
            disk.submit(() -> { }).get(10, TimeUnit.SECONDS);   // 串行执行器:这个空任务排在所有写之后

            List<AppendLog.Record> records = log(dir.resolve("a.jsonl")).read().records();
            assertEquals(50, records.size());
            for (int i = 0; i < 50; i++) {
                assertEquals(String.valueOf(i), records.get(i).string("i"));
            }
            assertFalse(threads.contains(Thread.currentThread().getName()), "磁盘写没有发生在调用线程上");
            assertEquals(50, log.readAsync().get(10, TimeUnit.SECONDS).records().size());
        } finally {
            disk.shutdownNow();
        }
    }

    @Test
    void aWriteThatFailsGoesToOnErrorNotToTheCaller(@TempDir Path dir) {
        AppendLog log = log(dir);   // 路径是个目录:写不进去
        AppendLog.Record shown = log.append("n", body("text", "x"));

        assertNotEquals(null, shown.id());
        assertEquals(1, errors.size());
    }
}
