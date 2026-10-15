package com.dwinovo.numen.api.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 水况片段:碰着水才写、只写事实;推送节奏由字符串变不变决定,所以头在水面上不写会每刻都变的数。 */
class WaterStateTest {

    @Test
    void notInWaterSaysNothing() {
        assertEquals("", WaterState.render(false, false, 300, 300, ""));
        assertEquals("", WaterState.render(false, false, 120, 300, ""), "刚出水氧气还在回也不写");
    }

    @Test
    void headUnderWaterSaysHowManySecondsOfAirAreLeft() {
        assertEquals("<water>In water, head under water; air left 12s.</water>", WaterState.render(true, true, 240, 300, ""));
        assertTrue(WaterState.render(true, true, 221, 300, "").contains("air left 12s"), "不满一秒按一秒算,不说还有 0 秒");
        assertTrue(WaterState.render(true, true, 0, 300, "").contains("air left 0s"));
    }

    @Test
    void headAboveWaterDoesNotWriteANumberThatChangesEveryTick() {
        String refilling = WaterState.render(true, false, 120, 300, "");
        assertEquals(refilling, WaterState.render(true, false, 124, 300, ""), "回气的那几秒整段不变,不会每刻推一次包");
        assertEquals("<water>In water, head above water; air refilling.</water>", refilling);
        assertEquals("<water>In water, head above water; air full.</water>", WaterState.render(true, false, 300, 300, ""));
    }

    @Test
    void airThatDoesNotRunDownSaysWhy() {
        assertEquals("<water>In water, head under water; air is not running down (water breathing).</water>",
                WaterState.render(true, true, 300, 300, "water breathing"));
        assertTrue(WaterState.render(true, true, 300, 300, "creative mode").contains("(creative mode)"));
    }
}
