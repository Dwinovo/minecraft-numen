package com.dwinovo.numen.mcp.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 现场缓冲:每同伴独立、全局道合流、限行数、按时间排。 */
class McpTranscriptTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void reset() {
        McpTranscript.clearAll();
    }

    @Test
    void lanes_are_per_companion() {
        McpTranscript.owner(alice, "给我烤个鱼");
        McpTranscript.say(bob, "好嘞");
        List<McpTranscript.Line> a = McpTranscript.view(alice);
        assertEquals(1, a.size());
        assertEquals(McpTranscript.Kind.OWNER, a.get(0).kind());
        assertEquals(1, McpTranscript.view(bob).size());
    }

    @Test
    void global_tool_lines_show_in_every_view() {
        McpTranscript.tool(null, "list_companions → Alice, Bob", false);
        McpTranscript.owner(alice, "hi");
        assertEquals(2, McpTranscript.view(alice).size());
        assertEquals(1, McpTranscript.view(bob).size());
    }

    @Test
    void view_is_ordered_and_capped() {
        for (int i = 0; i < McpTranscript.CAP + 20; i++) {
            McpTranscript.say(alice, "第" + i + "句");
        }
        List<McpTranscript.Line> v = McpTranscript.view(alice);
        assertEquals(McpTranscript.CAP, v.size());
        // 满了丢最老的:第 0..19 句不在,最后一句在
        assertEquals("第" + (McpTranscript.CAP + 19) + "句", v.get(v.size() - 1).text());
        for (int i = 1; i < v.size(); i++) {
            assertTrue(v.get(i - 1).ts() <= v.get(i).ts(), "时间乱序");
        }
    }

    @Test
    void blank_lines_are_dropped_and_emptiness_is_per_companion() {
        assertTrue(McpTranscript.isEmpty(alice));
        McpTranscript.say(alice, "   ");
        assertTrue(McpTranscript.isEmpty(alice), "空白行不该入账");
        McpTranscript.say(alice, "在了");
        assertFalse(McpTranscript.isEmpty(alice));
        assertTrue(McpTranscript.isEmpty(bob));
        McpTranscript.tool(null, "create_companion → ok", false);
        assertFalse(McpTranscript.isEmpty(bob), "全局道对每个同伴都可见");
    }

    @Test
    void tool_error_flag_survives() {
        McpTranscript.tool(alice, "mine → timed out", true);
        assertTrue(McpTranscript.view(alice).get(0).error());
    }
}
