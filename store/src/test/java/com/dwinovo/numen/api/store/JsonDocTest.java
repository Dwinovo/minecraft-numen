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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonDocTest {

    private final List<StoreException> errors = new ArrayList<>();

    private JsonDoc doc(Path file, JsonDoc.Format format) {
        return new JsonDoc(file, format, Io.direct(errors::add));
    }

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void absentIsEmptyAndTheVersionStaysOutOfTheContent(@TempDir Path dir) throws IOException {
        JsonDoc doc = doc(dir.resolve("sub").resolve("d.json"), JsonDoc.Format.of(1));
        assertEquals(Optional.empty(), doc.read());

        doc.write(obj("{\"a\":1}"));

        assertEquals(obj("{\"a\":1}"), doc.read().orElseThrow());
        assertEquals(1, obj(Files.readString(doc.file())).get("v").getAsInt(), "文件顶层带版本号");
        assertTrue(errors.isEmpty());
    }

    @Test
    void aFileWithoutAVersionIsVersionOneAndIsLiftedOnRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("d.json");
        Files.writeString(file, "{\"name\":\"旧\"}", StandardCharsets.UTF_8);
        JsonDoc doc = doc(file, JsonDoc.Format.of(3)
                .upgrade(1, d -> { d.addProperty("step1", true); return d; })
                .upgrade(2, d -> { d.addProperty("step2", true); return d; }));

        JsonObject read = doc.read().orElseThrow();

        assertEquals(obj("{\"name\":\"旧\",\"step1\":true,\"step2\":true}"), read, "两步都在内存里走完");
        assertEquals("{\"name\":\"旧\"}", Files.readString(file, StandardCharsets.UTF_8), "读不改磁盘");
        doc.write(read);
        assertEquals(3, obj(Files.readString(file)).get("v").getAsInt(), "下一次写换成新版");
    }

    @Test
    void aMiddleVersionOnlyRunsTheStepsAboveIt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("d.json");
        Files.writeString(file, "{\"v\":2,\"name\":\"中\"}", StandardCharsets.UTF_8);
        JsonDoc doc = doc(file, JsonDoc.Format.of(3)
                .upgrade(1, d -> { d.addProperty("step1", true); return d; })
                .upgrade(2, d -> { d.addProperty("step2", true); return d; }));

        assertEquals(obj("{\"name\":\"中\",\"step2\":true}"), doc.read().orElseThrow());
    }

    @Test
    void aNewerVersionIsRefusedRatherThanMisread(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("d.json");
        Files.writeString(file, "{\"v\":7}", StandardCharsets.UTF_8);

        assertThrows(StoreException.class, () -> doc(file, JsonDoc.Format.of(1)).read());
    }

    @Test
    void aCorruptFileThrowsInsteadOfPretendingToBeEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("d.json");
        Files.writeString(file, "{ 半截", StandardCharsets.UTF_8);
        assertThrows(StoreException.class, () -> doc(file, JsonDoc.Format.of(1)).read());

        Files.writeString(file, "[1,2]", StandardCharsets.UTF_8);
        assertThrows(StoreException.class, () -> doc(file, JsonDoc.Format.of(1)).read());
    }

    @Test
    void aFailingUpgradeIsReportedWithoutTouchingTheFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("d.json");
        Files.writeString(file, "{\"a\":1}", StandardCharsets.UTF_8);
        JsonDoc doc = doc(file, JsonDoc.Format.of(2).upgrade(1, d -> { throw new IllegalStateException("坏了"); }));

        assertThrows(StoreException.class, doc::read);
        assertEquals("{\"a\":1}", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void aWriteThatFailsLeavesTheOldFileAndReportsToOnError(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("d.json");
        JsonDoc doc = doc(file, JsonDoc.Format.of(1));
        doc.write(obj("{\"a\":1}"));
        Files.createDirectory(dir.resolve("d.json.tmp"));   // 临时文件的位置被占住

        doc.write(obj("{\"a\":2}"));

        assertEquals(1, errors.size());
        assertEquals(obj("{\"a\":1}"), doc.read().orElseThrow(), "写失败不伤原文件");
    }

    @Test
    void deleteRemovesTheFileAndMissingIsFine(@TempDir Path dir) {
        JsonDoc doc = doc(dir.resolve("d.json"), JsonDoc.Format.of(1));
        doc.write(obj("{}"));
        doc.delete();
        doc.delete();

        assertFalse(Files.exists(doc.file()));
        assertTrue(errors.isEmpty());
    }

    @Test
    void aFormatMissingAStepIsRejectedUpFront(@TempDir Path dir) {
        assertThrows(IllegalStateException.class, () -> doc(dir.resolve("d.json"), JsonDoc.Format.of(3).upgrade(1, d -> d)));
    }
}
