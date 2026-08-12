package com.dwinovo.numen.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageJsonExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void localesContainTheSameCompleteKeySet() {
        Map<String, String> en = LanguageJsonExporter.translations("en_us");
        Map<String, String> zh = LanguageJsonExporter.translations("zh_cn");

        assertTrue(en.size() >= 250);
        assertEquals(en.keySet(), zh.keySet());
        assertEquals("Chat", en.get("numen.tab.chat"));
        assertEquals("Settings", en.get("numen.tab.settings"));
        assertEquals("设置", zh.get("numen.tab.settings"));
        assertFalse(zh.get("numen.empty.no_companions").startsWith("numen."));
        en.forEach((key, value) -> assertFalse(value.isBlank() || value.equals(key), key));
        zh.forEach((key, value) -> assertFalse(value.isBlank() || value.equals(key), key));
    }

    @Test
    void exporterWritesUtf8JsonForBothLocales() throws Exception {
        LanguageJsonExporter.main(new String[]{tempDir.toString(), "numen_api"});

        Path en = tempDir.resolve("assets/numen_api/lang/en_us.json");
        Path zh = tempDir.resolve("assets/numen_api/lang/zh_cn.json");
        assertTrue(Files.isRegularFile(en));
        assertTrue(Files.isRegularFile(zh));
        assertTrue(Files.readString(en, StandardCharsets.UTF_8)
                .contains("\"numen.tab.chat\": \"Chat\""));
        assertTrue(Files.readString(zh, StandardCharsets.UTF_8)
                .contains("\"numen.tab.settings\": \"设置\""));
    }
}
