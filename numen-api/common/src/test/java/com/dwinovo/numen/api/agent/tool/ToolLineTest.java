package com.dwinovo.numen.api.agent.tool;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 一次调用怎么读:名字,有可说的就后面跟着;没有就只有名字。 */
class ToolLineTest {

    @Test
    void aCallReadsAsItsLabelThenItsSummary() {
        assertEquals("技能  lumber", ToolLine.of("技能", "lumber"));
        assertEquals("执行  挖东边那片铁矿", ToolLine.of(Component.literal("执行"), "挖东边那片铁矿").getString());
    }

    @Test
    void aCallWithNothingToSayReadsAsItsLabel() {
        assertEquals("技能", ToolLine.of("技能", ""));
        assertEquals("技能", ToolLine.of(Component.literal("技能"), "").getString());
    }

    @Test
    void theLabelKeyIsTheToolNameUnderTheConventionPrefix() {
        assertEquals("numen.tool.lua", ToolLine.key("lua"));
    }
}
