package com.dwinovo.numen.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 思考开关×推理强度 ↔ 单存储值的双向映射,含往返一致性。 */
class ReasoningChoiceTest {

    @Test
    void storedValueDecomposesToSwitchAndLevel() {
        assertEquals(ReasoningChoice.SWITCH_AUTO, ReasoningChoice.switchIndex("auto"));
        assertEquals(ReasoningChoice.SWITCH_AUTO, ReasoningChoice.switchIndex(null));
        assertEquals(ReasoningChoice.SWITCH_OFF, ReasoningChoice.switchIndex("off"));
        assertEquals(ReasoningChoice.SWITCH_ON, ReasoningChoice.switchIndex("high"));
        assertEquals(ReasoningChoice.LEVEL_HIGH, ReasoningChoice.levelIndex("high"));
        assertEquals(ReasoningChoice.LEVEL_MEDIUM, ReasoningChoice.levelIndex("auto"));
    }

    @Test
    void composeCoversAllSwitchStates() {
        assertEquals("auto", ReasoningChoice.compose(ReasoningChoice.SWITCH_AUTO, ReasoningChoice.LEVEL_HIGH));
        assertEquals("off", ReasoningChoice.compose(ReasoningChoice.SWITCH_OFF, ReasoningChoice.LEVEL_LOW));
        assertEquals("low", ReasoningChoice.compose(ReasoningChoice.SWITCH_ON, ReasoningChoice.LEVEL_LOW));
        assertEquals("high", ReasoningChoice.compose(ReasoningChoice.SWITCH_ON, ReasoningChoice.LEVEL_HIGH));
    }

    @Test
    void roundTripIsStableForEveryStoredValue() {
        for (String stored : new String[]{"auto", "off", "low", "medium", "high"}) {
            String back = ReasoningChoice.compose(
                    ReasoningChoice.switchIndex(stored), ReasoningChoice.levelIndex(stored));
            assertEquals(stored, back, "往返漂移: " + stored);
        }
    }
}
