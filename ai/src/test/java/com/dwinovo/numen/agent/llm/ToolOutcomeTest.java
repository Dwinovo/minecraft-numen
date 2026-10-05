package com.dwinovo.numen.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 成败判据:只认权威声明(TaskResult 的 success / 派发层的 ERROR 兜底),其余不猜。 */
class ToolOutcomeTest {

    @Test
    void taskResultEnvelopeIsAuthoritative() {
        assertTrue(ToolOutcome.failed("{\"success\":false,\"message\":\"unreachable\"}"));
        assertFalse(ToolOutcome.failed("{\"success\":true,\"message\":\"arrived\"}"));
        assertTrue(ToolOutcome.failed("  {\"success\": false, \"timed_out\": true}"));
    }

    @Test
    void dispatcherErrorPrefixIsFailure() {
        assertTrue(ToolOutcome.failed("ERROR: tool threw NullPointerException"));
        assertTrue(ToolOutcome.failed("  ERROR no such tool"));
    }

    @Test
    void plainDataResultsAreNotFailures() {
        // 旧实现的误判源:正文里出现 error 字样的正常结果被整条标红。
        assertFalse(ToolOutcome.failed("{\"blocks\":[\"stone\"],\"note\":\"no error found\"}"));
        assertFalse(ToolOutcome.failed("{\"error_count\":0}"));
        assertFalse(ToolOutcome.failed("看到 3 只羊,没有敌对生物"));
        assertFalse(ToolOutcome.failed("{\"items\":[]}"));
    }

    @Test
    void malformedOrEmptyIsNotFailure() {
        assertFalse(ToolOutcome.failed(null));
        assertFalse(ToolOutcome.failed(""));
        assertFalse(ToolOutcome.failed("   "));
        assertFalse(ToolOutcome.failed("{\"success\":"));          // 半截 JSON
        assertFalse(ToolOutcome.failed("{\"success\":\"false\"}")); // 字符串不是布尔,不认
    }

    /** 旧档里的程序回执还带着 data;模型读到的只有文字。 */
    private static final String RECEIPT = "{\"success\":false,\"message\":\"The script stopped at line 2\\nstderr:\\nline 2 f: boom\","
            + "\"data\":{\"status\":\"error\",\"calls\":2,\"returned\":[1,2,3]}}";

    @Test
    void theModelReadsAnEnvelopesMessageAndNothingElse() {
        assertEquals("The script stopped at line 2\nstderr:\nline 2 f: boom", ToolOutcome.modelText(RECEIPT));
        assertEquals("plans written", ToolOutcome.modelText(ToolOutcome.success("plans written")));
    }

    @Test
    void whatIsNotAnEnvelopeIsHandedOverAsItIs() {
        assertEquals("技能正文\n第二行", ToolOutcome.modelText("技能正文\n第二行"));
        assertEquals("{\"items\":[]}", ToolOutcome.modelText("{\"items\":[]}"), "没有 success 与 message 的 JSON 不是信封");
        assertEquals("{\"success\":true}", ToolOutcome.modelText("{\"success\":true}"));
        assertEquals("{\"success\":\"yes\",\"message\":\"m\"}", ToolOutcome.modelText("{\"success\":\"yes\",\"message\":\"m\"}"));
        assertEquals("{\"success\":", ToolOutcome.modelText("{\"success\":"), "半截 JSON");
        assertEquals(null, ToolOutcome.modelText(null));
    }
}
