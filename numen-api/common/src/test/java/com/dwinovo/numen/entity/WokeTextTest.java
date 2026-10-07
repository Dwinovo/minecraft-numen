package com.dwinovo.numen.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 醒来那句话说了什么。
 *
 * <p>钉的是「她能不能分辨自己为什么醒」:天亮醒意味着夜过去了、可以接着干活;还是夜里就醒
 * 意味着有外力,得先看周围。这两种混成一句话,她就只能每次醒来都重新侦察一遍。
 */
class WokeTextTest {

    /** 天亮自然醒:得说清夜过去了,否则她不知道自己等的那件事到底成没成。 */
    @Test
    void daybreakSaysTheNightIsOver() {
        String text = Companions.wokeText(true, null);
        assertTrue(text.contains("天亮"), text);
        assertTrue(text.contains("夜过去了"), text);
    }

    /** 还是夜里就醒了:那不是自然醒,得让她先看周围,而不是当成"睡好了"。 */
    @Test
    void wakingWhileStillDarkReadsAsInterrupted() {
        String text = Companions.wokeText(false, null);
        assertTrue(text.contains("天还没亮"), text);
        assertTrue(text.contains("看看周围"), text);
        assertFalse(text.contains("夜过去了"), text);
    }

    /** 挨打醒:凶手的名字必须在话里,那是她下一步唯一的依据。 */
    @Test
    void beingHurtAwakeNamesWhatHitHer() {
        String text = Companions.wokeText(false, "僵尸");
        assertTrue(text.contains("僵尸"), text);
        assertTrue(text.contains("打醒"), text);
    }

    /**
     * 天亮 <b>且</b> 挨打:两件都真,先说挨打——夜过没过去可以等一等再想,身上正挨着的
     * 那一下不能。但天亮这件事不能因此丢掉,她得知道现在是白天。
     */
    @Test
    void whenBothAreTrueTheAttackLeadsAndDaylightStillGetsSaid() {
        String text = Companions.wokeText(true, "骷髅");
        assertTrue(text.indexOf("骷髅") < text.indexOf("天已经亮了"), text);
    }
}
