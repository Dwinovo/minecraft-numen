package com.dwinovo.numen.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "她多久开一次口"。
 *
 * <p>这层决定的是主人对这个模组的第一印象:开口太勤是聒噪,永不开口是死气沉沉,
 * 而两者都会被当成 BUG。所以三条倾倒理由每一条都得钉住,尤其是<b>攒久了也要说</b>
 * ——少了它,高档位就退化成永久沉默。
 */
class InboxPolicyTest {

    private static final long JUST_NOW = 0L;

    // ---- urgent ----

    @Test
    void urgentDrainsImmediatelyNoMatterTheLevel() {
        // "她不知道就会做错事" —— 档位拉到最沉默也拦不住
        assertTrue(InboxPolicy.shouldDrain(true, 1, JUST_NOW, InboxPolicy.MAX_LEVEL));
    }

    @Test
    void urgentOnAnEmptyQueueIsMeaningless() {
        // urgent 事件自己就在队列里;队列空 = 没有 urgent 可言
        assertFalse(InboxPolicy.shouldDrain(true, 0, JUST_NOW, 1));
    }

    // ---- 攒够了 ----

    @Test
    void levelOneReactsToEverything() {
        assertTrue(InboxPolicy.shouldDrain(false, 1, JUST_NOW, 1), "1 档 = 一有动静就说");
    }

    @Test
    void higherLevelsWaitForMore() {
        assertFalse(InboxPolicy.shouldDrain(false, 2, JUST_NOW, 5), "5 档攒够 5 条才说");
        assertTrue(InboxPolicy.shouldDrain(false, 5, JUST_NOW, 5));
    }

    @Test
    void emptyQueueNeverDrains() {
        for (int lv = InboxPolicy.MIN_LEVEL; lv <= InboxPolicy.MAX_LEVEL; lv++) {
            assertFalse(InboxPolicy.shouldDrain(false, 0, 999_999_999L, lv), "档位 " + lv);
        }
    }

    // ---- 攒久了 ----

    @Test
    void sittingTooLongDrainsEvenBelowTheThreshold() {
        // 少了这条:阈值 10 而只发生了 3 件事,那 3 条会一直躺到主人下次开口
        int lv = 10;
        assertFalse(InboxPolicy.shouldDrain(false, 3, 60_000L, lv), "才一分钟,再等等");
        assertTrue(InboxPolicy.shouldDrain(false, 3, InboxPolicy.maxWaitMs(lv), lv), "躺够了就得说");
    }

    @Test
    void patienceGrowsWithTheLevel() {
        long prev = -1;
        for (int lv = InboxPolicy.MIN_LEVEL; lv <= InboxPolicy.MAX_LEVEL; lv++) {
            long wait = InboxPolicy.maxWaitMs(lv);
            assertTrue(wait > prev, "档位越高越沉得住气,档位 " + lv + " 却是 " + wait);
            prev = wait;
        }
        assertTrue(InboxPolicy.maxWaitMs(10) <= 60 * 60_000L, "再沉默也不该超过一小时");
    }

    // ---- 档位边界 ----

    @Test
    void levelsAreClampedNotTrusted() {
        // 档位来自配置文件,主人可以手改成任何数
        assertEquals(InboxPolicy.MIN_LEVEL, InboxPolicy.clampLevel(0));
        assertEquals(InboxPolicy.MIN_LEVEL, InboxPolicy.clampLevel(-99));
        assertEquals(InboxPolicy.MAX_LEVEL, InboxPolicy.clampLevel(999));
        assertEquals(7, InboxPolicy.clampLevel(7));
    }

    @Test
    void thresholdIsTheLevelItself() {
        assertEquals(1, InboxPolicy.threshold(1));
        assertEquals(10, InboxPolicy.threshold(10));
        assertEquals(10, InboxPolicy.threshold(50), "越界也不能变成无限沉默");
    }

    @Test
    void defaultLevelIsNeitherChattyNorMute() {
        int d = InboxPolicy.DEFAULT_LEVEL;
        assertTrue(d > InboxPolicy.MIN_LEVEL && d < InboxPolicy.MAX_LEVEL, "缺省档不该落在两个极端上");
    }
}
