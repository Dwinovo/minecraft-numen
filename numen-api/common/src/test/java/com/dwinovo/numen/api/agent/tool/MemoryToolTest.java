package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static NoteBook notes;

    @BeforeAll
    static void books() {
        notes = new NoteBook(new com.dwinovo.numen.api.home.Home(homes.resolve(HER.toString()),
                com.dwinovo.numen.api.NumenIo.inline("test")), () -> 7);
    }

    /** 她的札记在手的锚点;技能这里用不上。 */
    private static com.dwinovo.numen.api.agent.tool.ToolAnchor anchor() {
        return new com.dwinovo.numen.api.agent.tool.ToolAnchor() {
            @Override
            public UUID entityUuid() {
                return HER;
            }

            @Override
            public NoteBook notes() {
                return notes;
            }

            @Override
            public com.dwinovo.numen.api.agent.skill.SkillRegistry skills() {
                throw new UnsupportedOperationException();
            }

            @Override
            public QuestionDesk questions() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** 调一次:参数是那段 JSON 文本,交回这次调用的结果。 */
    private static JsonObject call(String args) {
        AtomicReference<String> result = new AtomicReference<>();
        new MemoryTool().invoke(new ToolCall("m1", MemoryTool.NAME, args, anchor(), result::set));
        return JsonParser.parseString(result.get()).getAsJsonObject();
    }

    @Test
    void aNoteIsRememberedRecalledAndForgotten() {
        JsonObject wrote = call("{\"command\": \"remember\", \"name\": \"main-base\", \"description\": "
                + "\"main base -340,68,120\", \"content\": \"door faces east\"}");
        assertTrue(wrote.get("success").getAsBoolean(), wrote.toString());
        assertTrue(notes.formatXml().contains("main base -340,68,120"),
                "the description is the line in <memory>: " + notes.formatXml());

        JsonObject read = call("{\"command\": \"recall\", \"name\": \"main-base\"}");
        assertTrue(read.get("message").getAsString().contains("door faces east"), read.toString());

        assertTrue(call("{\"command\": \"forget\", \"name\": \"main-base\"}").get("success").getAsBoolean());
        assertNull(notes.read("main-base"));
        JsonObject gone = call("{\"command\": \"recall\", \"name\": \"main-base\"}");
        assertFalse(gone.get("success").getAsBoolean());
        assertTrue(gone.get("message").getAsString().startsWith("no note named main-base"), gone.toString());
    }

    /** 外部连进来的大脑没有每轮注入的索引:recall 不点名就交回索引。 */
    @Test
    void recallWithNoNameReturnsTheIndex() {
        assertEquals("(you have no notes)", call("{\"command\": \"recall\"}").get("message").getAsString());
        call("{\"command\": \"remember\", \"name\": \"main-base\", \"description\": \"main base -340,68,120\"}");

        String index = call("{\"command\": \"recall\"}").get("message").getAsString();

        assertTrue(index.startsWith("<memory") && index.contains("main base -340,68,120"), index);
    }

    /** 札记只有三种:写别的当场拒,什么都不落盘。 */
    @Test
    void aNoteOfAnUnknownTypeIsRefused() {
        JsonObject wrote = call("{\"command\": \"remember\", \"name\": \"chores\", \"description\": \"sweep\", "
                + "\"type\": \"todo\"}");
        assertFalse(wrote.get("success").getAsBoolean());
        assertTrue(wrote.get("message").getAsString().contains("owner, world, lesson"), wrote.toString());
        assertNull(notes.read("chores"));
    }
}
