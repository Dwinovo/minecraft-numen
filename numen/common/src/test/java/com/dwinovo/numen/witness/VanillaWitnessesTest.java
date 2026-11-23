package com.dwinovo.numen.witness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 一次动作前后血量的那一条事实:按原版点数写从多少到多少,浮点误差不算变。 */
class VanillaWitnessesTest {

    /** 点一下的当口血量被设成别的(像神社复活把使用者设到 25%):写明从 20 到 5。 */
    @Test
    void aHealthChangeSaysFromWhatToWhat() {
        assertEquals("health: 20 -> 5", VanillaWitnesses.healthChange(20.0f, 5.0f));
    }

    @Test
    void aFractionalHealthKeepsOneDecimal() {
        assertEquals("health: 20 -> 7.5", VanillaWitnesses.healthChange(20.0f, 7.5f));
    }

    @Test
    void aDifferenceWithinFloatErrorIsNoChange() {
        assertEquals("", VanillaWitnesses.healthChange(20.0f, 20.0f + 1.0E-4f));
    }
}
