package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.api.entity.Controls;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "她动了没有"的判据:和 YSM 客户端一样,前后左右、跳、潜行任一有输入。 */
class EmotesTest {

    @Test
    void idleIsNotMoving() {
        assertFalse(Emotes.moved(new Controls(), false));
    }

    @Test
    void anyMovementKeyCounts() {
        for (Controls.Key key : new Controls.Key[]{Controls.Key.FORWARD, Controls.Key.BACK, Controls.Key.LEFT,
                Controls.Key.RIGHT, Controls.Key.JUMP}) {
            Controls controls = new Controls();
            controls.press(key);
            assertTrue(Emotes.moved(controls, false), key.name());
        }
    }

    @Test
    void sneakingBodyCountsEvenWithoutTheKey() {
        assertTrue(Emotes.moved(new Controls(), true));
    }

    @Test
    void sprintAloneIsNotMoving() {
        Controls controls = new Controls();
        controls.press(Controls.Key.SPRINT);
        assertFalse(Emotes.moved(controls, false));
    }
}
