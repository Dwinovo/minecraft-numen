package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.memory.NoteBook;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 札记工具,从模型的入口调:记一条、读回它的正文、忘掉它;记下的那一行照旧出现在注入的 {@code <memory>} 里。札记落在临时目录里。
 */
class MemoryToolTest {

    @TempDir
    static Path homes;

    private static final UUID HER = UUID.randomUUID();

    @BeforeAll
    static void books() {
        NoteBook.init(uuid -> homes.resolve(uuid.toString()), () -> 7);
    }

    /** 调一次:参数是那段 JSON 文本,交回这次调用的结果。 */
    private static JsonObject call(String args) {
        AtomicReference<String> result = new AtomicReference<>();
        new MemoryTool().invoke(new ToolCall("m1", MemoryTool.NAME, args, () -> HER, result::set));
        return JsonParser.parseString(result.get()).getAsJsonObject();
    }

    @Test
    void aNoteIsRememberedRecalledAndForgotten() {
        JsonObject wrote = call("{\"command\": \"remember\", \"name\": \"main-base\", \"description\": "
                + "\"main base -340,68,120\", \"content\": \"door faces east\"}");
        assertTrue(wrote.get("success").getAsBoolean(), wrote.toString());
        assertTrue(NoteBook.of(HER).formatXml().contains("main base -340,68,120"),
                "the description is the line in <memory>: " + NoteBook.of(HER).formatXml());

        JsonObject read = call("{\"command\": \"recall\", \"name\": \"main-base\"}");
        assertTrue(read.get("message").getAsString().contains("door faces east"), read.toString());

        assertTrue(call("{\"command\": \"forget\", \"name\": \"main-base\"}").get("success").getAsBoolean());
        assertNull(NoteBook.of(HER).read("main-base"));
        JsonObject gone = call("{\"command\": \"recall\", \"name\": \"main-base\"}");
        assertFalse(gone.get("success").getAsBoolean());
        assertTrue(gone.get("message").getAsString().startsWith("no note named main-base"), gone.toString());
    }

    /** 札记只有三种:写别的当场拒,什么都不落盘。 */
    @Test
    void aNoteOfAnUnknownTypeIsRefused() {
        JsonObject wrote = call("{\"command\": \"remember\", \"name\": \"chores\", \"description\": \"sweep\", "
                + "\"type\": \"todo\"}");
        assertFalse(wrote.get("success").getAsBoolean());
        assertTrue(wrote.get("message").getAsString().contains("owner, world, lesson"), wrote.toString());
        assertNull(NoteBook.of(HER).read("chores"));
    }
}
