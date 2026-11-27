package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.mcp.client.RemoteMcpTool;
import com.dwinovo.numen.mcp.protocol.Tool;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 每个工具说一次调用在主人面板上读作什么:一句短话,没有值得说的是空串;工具没登记或参数读不出来也是空串。 */
class ToolSummaryTest {

    private static String summary(NumenTool tool, String arguments) {
        return NumenTool.summaryOf(tool, arguments);
    }

    @Test
    void aProgramReadsAsItsDescription() {
        String args = ScriptTool.args("minecraft.work.dig({})", "Mine the iron vein\nto the east").toString();
        assertEquals("Mine the iron vein to the east", summary(new ScriptTool(), args));
    }

    @Test
    void aProgramWithNoUsableDescriptionReadsAsNothing() {
        assertEquals("", summary(new ScriptTool(), "{\"code\":\"x\"}"));
    }

    @Test
    void aSkillReadsAsItsNameAndFile() {
        assertEquals("lumber", summary(new SkillTool(), "{\"skill\":\"lumber\"}"));
        assertEquals("lumber references/saw.md",
                summary(new SkillTool(), "{\"skill\":\"lumber\",\"file\":\"references/saw.md\"}"));
        assertEquals("", summary(new SkillTool(), "{}"));
    }

    @Test
    void aPlanReadsAsTheStepInProgress() {
        assertEquals("mine iron", summary(new TodoTool(),
                "{\"items\":[\"[x] find iron\",\"[>] mine iron\",\"[ ] smelt it\"]}"));
        assertEquals("", summary(new TodoTool(), "{\"items\":[\"[x] find iron\"]}"));
        assertEquals("", summary(new TodoTool(), "{\"items\":\"not a plan\"}"));
    }

    @Test
    void aQuestionReadsAsTheHeaderOfItsFirstQuestion() {
        assertEquals("Furnace", summary(new AskUserQuestionTool(),
                "{\"questions\":[{\"question\":\"Which?\",\"header\":\"Furnace\",\"options\":[]}]}"));
        assertEquals("", summary(new AskUserQuestionTool(), "{}"));
    }

    @Test
    void aNoteCallReadsAsItsCommandAndNote() {
        assertEquals("remember main-base", summary(new MemoryTool(), "{\"command\":\"remember\",\"name\":\"main-base\"}"));
        assertEquals("recall", summary(new MemoryTool(), "{\"command\":\"recall\"}"));
        assertEquals("", summary(new MemoryTool(), "{}"));
    }

    @Test
    void anOutsideToolReadsAsItsClippedArguments() {
        NumenTool remote = new RemoteMcpTool("srv", "ns:srv", null,
                new Tool("search", null, null, new JsonObject(), null, null), Runnable::run, new HashSet<>());
        assertEquals("{\"q\":\"iron\"}", summary(remote, "{\"q\":\"iron\"}"));
        assertEquals("", summary(remote, "{}"));
        String long_ = summary(remote, "{\"q\":\"" + "x".repeat(100) + "\"}");
        assertTrue(long_.length() == 45 && long_.endsWith("…"), long_);
    }

    @Test
    void anUnregisteredToolOrUnreadableArgumentsReadAsNothing() {
        assertEquals("", summary(null, "{\"skill\":\"lumber\"}"));
        assertEquals("", summary(new SkillTool(), "not json"));
        assertEquals("", summary(new SkillTool(), null));
    }
}
