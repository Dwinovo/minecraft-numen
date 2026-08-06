package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「逃掉了没有」。
 *
 * <p>钉的是这套判据存在的理由:它回答的是<b>还会不会挨打</b>,不是距离、也不是仇恨。
 */
class PursuitTest {

    private static Map<Integer, Double> at(double distanceSquared) {
        Map<Integer, Double> m = new LinkedHashMap<>();
        m.put(1, distanceSquared);
        return m;
    }

    /** 没人追,立刻算数 —— 不必再等那两秒。 */
    @Test
    void nobodyChasingIsEscapedRightAway() {
        Pursuit p = new Pursuit();
        p.observe(Map.of(), 100);
        assertTrue(p.escaped(100));
    }

    /** 它还在缩短距离:无论过多久都不算逃掉。 */
    @Test
    void somethingStillClosingIsNeverEscaped() {
        Pursuit p = new Pursuit();
        for (int t = 0; t < 200; t++) {
            p.observe(at(400.0 - t), t);
        }
        assertFalse(p.escaped(200));
    }

    /**
     * 卡住的怪:它有仇恨、离得也不远,但再也逼近不了。<b>这就是"逃掉"</b>
     * ——柱子下面的僵尸够不着她。
     */
    @Test
    void aChaserThatStopsGainingCountsAsEscaped() {
        Pursuit p = new Pursuit();
        p.observe(at(100.0), 0);
        for (int t = 1; t <= 41; t++) {
            p.observe(at(100.0), t);   // 距离一直没变
        }
        assertTrue(p.escaped(41));
    }

    /** 但得稳住够久 —— 它绕个路的空档不能被当成甩掉了。 */
    @Test
    void aBriefPauseIsNotEnough() {
        Pursuit p = new Pursuit();
        p.observe(at(100.0), 0);
        p.observe(at(100.0), 10);
        assertFalse(p.escaped(10));
    }

    /** 停滞之后又逼近上来:重新计时,不能吃老本。 */
    @Test
    void closingAgainRestartsTheClock() {
        Pursuit p = new Pursuit();
        p.observe(at(100.0), 0);
        p.observe(at(100.0), 30);
        p.observe(at(50.0), 31);      // 又追近了:计时清零
        p.observe(at(50.0), 32);      // 从这一刻起才重新开始停滞
        assertFalse(p.escaped(70), "从第 32 刻重新计,第 70 刻还不够");
        assertTrue(p.escaped(72));
    }

    /**
     * 原地站着的怪会有浮点抖动与碰撞箱微动。没有门槛的话它每刻都在"刷新最近距离",
     * 停滞就永远不成立,她也就永远逃不掉。
     */
    @Test
    void jitterDoesNotCountAsClosing() {
        Pursuit p = new Pursuit();
        p.observe(at(100.0), 0);
        for (int t = 1; t <= 41; t++) {
            p.observe(at(100.0 - t * 0.001), t);   // 每刻挪一丝
        }
        assertTrue(p.escaped(41));
    }
}
