package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.plan.Mobility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 创造飞行的受理只看许不许飞:许飞就受理;不许飞当场拒绝,缺的是飞行,并带上鞘翅还剩的耐久——宿主据此在提示里多给一条滑翔
 * (那句话在 {@code NavTextTest})。
 */
class FlightAdmissionTest {

    @Test
    void aBodyThatMayFlyIsAdmitted() {
        assertNull(Voyage.flightAdmission(new Mobility(true, false, Mobility.VANILLA_FLYING_SPEED, 0, 0, 0)).lack());
    }

    @Test
    void aBodyThatMayNotFlyIsRefusedForLackOfFlight() {
        assertEquals(new Lack(Lack.Why.NO_FLIGHT, 0, 0), Voyage.flightAdmission(Mobility.WALKER).lack());
    }

    @Test
    void anElytraOnABodyThatMayNotFlyIsNamedSoTheHostCanOfferAGlide() {
        assertEquals(new Lack(Lack.Why.NO_FLIGHT, 0, 300),
                Voyage.flightAdmission(new Mobility(false, false, Mobility.VANILLA_FLYING_SPEED, 300, 8, 1)).lack());
    }
}
