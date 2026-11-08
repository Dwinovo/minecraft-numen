package com.dwinovo.numen.api.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 召唤当场就能判的两道闸:名字要合 Minecraft 的命名规则;服主定了每人至多几只(0 是不限),到数了再召新的不行,叫醒已有的那只
 * 不占名额。
 */
class SummonAdmissionTest {

    @Test
    void aSecondNewCompanionIsOverALimitOfOne() {
        assertTrue(Companions.overLimit(1, false, 1));
    }

    @Test
    void wakingTheOneSheHasTakesNoSlot() {
        assertFalse(Companions.overLimit(1, true, 1));
    }

    @Test
    void theFirstOneAndNoLimitAreFine() {
        assertFalse(Companions.overLimit(1, false, 0));
        assertFalse(Companions.overLimit(0, false, 5));
    }

    @Test
    void aNameThatIsNotAPlayerNameIsRefused() {
        assertFalse(MojangSkins.validName("x"));
        assertTrue(MojangSkins.validName("gametest_lim_a"));
    }
}
