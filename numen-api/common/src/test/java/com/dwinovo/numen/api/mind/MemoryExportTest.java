package com.dwinovo.numen.api.brain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 导出的 zip:她的对话日志和每条札记,原样的文件;别的东西(收件箱、绑定、台账)不在里面。 */
class MemoryExportTest {

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    void theChatLogAndEveryNoteAreInTheZip(@TempDir Path home) throws IOException {
        Files.writeString(home.resolve("chat.jsonl"), "{\"type\":\"message\"}\n");
        Files.createDirectories(home.resolve("memory"));
        Files.writeString(home.resolve("memory").resolve("iron.md"), "铁在东边");
        Files.writeString(home.resolve("memory").resolve("home.md"), "家在山脚");
        Files.writeString(home.resolve("inbox.jsonl"), "不在里面");
        Files.writeString(home.resolve("binding.json"), "{}");

        Map<String, String> files = unzip(MemoryExport.zip(home));

        assertEquals(Map.of("chat.jsonl", "{\"type\":\"message\"}\n", "memory/home.md", "家在山脚",
                "memory/iron.md", "铁在东边"), files);
    }

    @Test
    void aCompanionWhoNeverSaidAnythingExportsAnEmptyZip(@TempDir Path home) throws IOException {
        assertTrue(unzip(MemoryExport.zip(home)).isEmpty());
    }
}
