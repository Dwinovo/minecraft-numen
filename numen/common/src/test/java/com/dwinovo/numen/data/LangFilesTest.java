package com.dwinovo.numen.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Numen 自己的语言文件是手写的(没有数据生成兜着),中英两份的键必须一一对应,每条译文非空。 */
class LangFilesTest {

    private static JsonObject read(String locale) throws Exception {
        try (var in = LangFilesTest.class.getResourceAsStream("/assets/numen/lang/" + locale + ".json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void englishAndChineseHaveTheSameKeys() throws Exception {
        JsonObject en = read("en_us");
        JsonObject zh = read("zh_cn");
        assertEquals(new TreeSet<>(en.keySet()), new TreeSet<>(zh.keySet()));
        for (var entry : en.entrySet()) {
            assertFalse(entry.getValue().getAsString().isBlank(), entry.getKey());
            assertFalse(zh.get(entry.getKey()).getAsString().isBlank(), entry.getKey());
        }
    }
}
