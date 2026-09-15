package com.dwinovo.numen.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件身体状态片段的变化检测。整段按字符串比:相同不推、变了推——每 tick 都检查一次,
 * 判错了要么她读到的是旧状态,要么每 tick 推一个包。
 */
class CompanionStateWatchTest {

    @Test
    void withoutAnyPluginFragmentNothingIsEverPushedForIt() {
        CompanionStateWatch watch = new CompanionStateWatch();

        assertFalse(watch.bodyStateChangedSince(""), "没有插件要说什么,就不因为它推包");
        assertFalse(watch.bodyStateChangedSince(""));
    }

    @Test
    void theSameFragmentIsNotPushedTwice() {
        CompanionStateWatch watch = new CompanionStateWatch();

        assertTrue(watch.bodyStateChangedSince("<curios>ring: gold_ring</curios>"), "第一次见到就是变了");
        assertFalse(watch.bodyStateChangedSince("<curios>ring: gold_ring</curios>"), "相同不推");
        assertFalse(watch.bodyStateChangedSince("<curios>ring: gold_ring</curios>"));
    }

    @Test
    void aChangedOrVanishedFragmentIsPushed() {
        CompanionStateWatch watch = new CompanionStateWatch();
        watch.bodyStateChangedSince("<curios>ring: gold_ring</curios>");

        assertTrue(watch.bodyStateChangedSince("<curios>ring: iron_ring</curios>"), "换了一枚就推");
        assertTrue(watch.bodyStateChangedSince(""), "摘光了也要推:客户端手里那份得清掉");
        assertFalse(watch.bodyStateChangedSince(""));
    }
}
