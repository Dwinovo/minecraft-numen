package com.dwinovo.numen.task.chain;

import com.dwinovo.numen.pathing.plan.Breath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 换气本能什么时候该接管:游到最近能呼吸处的工夫,算上余量,肺里撑不住了。规则全在寻路的 {@link Breath}。 */
class BreathChainTest {

    @Test
    void aFullLungWithAirCloseByLeavesHerAlone() {
        assertFalse(BreathChain.mustSurface(new Breath(300, 300, 0, 0, false), 50), "满氧气离水面五格:不用上去");
    }

    @Test
    void surfacesWhenTheSwimUpWouldEatIntoTheReserve() {
        Breath breath = new Breath(300, 300, 0, 0, false);
        double lastSafe = 300 - Breath.RESERVE;
        assertFalse(BreathChain.mustSurface(breath, lastSafe), "游完恰好还剩余量:还撑得住");
        assertTrue(BreathChain.mustSurface(breath, lastSafe + 1), "游完不够余量:该上去了");
    }

    @Test
    void theFartherTheAirTheEarlierItTakesTheBody() {
        Breath half = new Breath(150, 300, 0, 0, false);
        assertFalse(BreathChain.mustSurface(half, 80));
        assertTrue(BreathChain.mustSurface(half, 100), "同样的氧气,头顶封得远,先上去");
    }

    @Test
    void waterBreathingStretchesTheTrigger() {
        assertFalse(BreathChain.mustSurface(new Breath(100, 300, 0, 600, false), 500), "水下呼吸效果先扣,肺里没动");
        assertFalse(BreathChain.mustSurface(new Breath(300, 300, 0, Double.POSITIVE_INFINITY, false), 5000),
                "不扣氧的身体(创造模式)永远不触发");
        assertTrue(BreathChain.mustSurface(new Breath(100, 300, 0, 0, false), 500), "没有效果就是照常扣");
    }
}
