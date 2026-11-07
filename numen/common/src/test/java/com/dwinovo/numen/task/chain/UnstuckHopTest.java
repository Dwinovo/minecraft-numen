package com.dwinovo.numen.task.chain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 脱困的挣脱不把飞着的身体从飞行里跳下来:创造飞行里跳键是升,七刻内连按两下会把飞行关掉;挣脱走着时每五刻跳一下,飞着时一下都
 * 不跳,只转向、往前。
 */
class UnstuckHopTest {

    @Test
    void aFlyingBodyNeverPressesJumpDuringAWholeBurst() {
        for (int left = 30; left >= 1; left--) {
            assertFalse(UnstuckChain.hops(true, left), "a flying body pressed jump with " + left + " ticks left");
        }
    }

    @Test
    void aWalkingBodyHopsEveryFifthTick() {
        int hops = 0;
        for (int left = 30; left >= 1; left--) {
            hops += UnstuckChain.hops(false, left) ? 1 : 0;
        }
        assertEquals(6, hops);
    }
}
