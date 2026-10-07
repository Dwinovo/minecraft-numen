package com.dwinovo.numen.pathing.drive;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卡没卡住:身体一直没挪、也没干成活,一秒之后就不算在推进(宿主的脱困反射读的就是它);挪动、干活、等规划的结论都算推进。
 * 一步超过按估价给的期限就算这一步卡住,慢挖的一步期限跟着估价长。
 */
class WatchdogTest {

    private static final Vec3 HERE = new Vec3(5.5, 64, 5.5);

    @Test
    void aBodyPinnedInPlaceStopsProgressingAfterASecond() {
        Watchdog dog = new Watchdog();
        dog.begin(10, HERE);
        for (int i = 0; i < 19; i++) {
            dog.observe(HERE, false);
        }
        assertTrue(dog.progressing(), "不到一秒还不算");
        dog.observe(HERE, false);
        assertFalse(dog.progressing());
    }

    @Test
    void jitterWithinATenthOfABlockIsStillPinned() {
        Watchdog dog = new Watchdog();
        dog.begin(10, HERE);
        for (int i = 0; i < 40; i++) {
            dog.observe(HERE.add((i % 2) * 0.05, 0, 0), false);
        }
        assertFalse(dog.progressing());
    }

    @Test
    void aMovingBodyIsProgressing() {
        Watchdog dog = new Watchdog();
        dog.begin(10, HERE);
        for (int i = 0; i < 40; i++) {
            dog.observe(HERE.add(i * 0.2, 0, 0), false);
        }
        assertTrue(dog.progressing());
    }

    @Test
    void workDoneInPlaceIsProgress() {
        Watchdog dog = new Watchdog();
        dog.begin(100, HERE);
        for (int i = 0; i < 40; i++) {
            dog.observe(HERE, i % 10 == 0);
        }
        assertTrue(dog.progressing(), "挖掘每几刻有进度就不是卡住");
    }

    @Test
    void waitingForARouteIsProgress() {
        Watchdog dog = new Watchdog();
        dog.begin(10, HERE);
        for (int i = 0; i < 30; i++) {
            dog.observe(HERE, false);
        }
        assertFalse(dog.progressing());
        dog.waiting(HERE);
        assertTrue(dog.progressing(), "等规划的结论有界,总会回来");
    }

    @Test
    void aStepOverrunsTwiceItsEstimatePlusThreeSeconds() {
        Watchdog dog = new Watchdog();
        dog.begin(20, HERE);
        for (int i = 0; i < 100; i++) {
            dog.observe(HERE.add(i * 0.2, 0, 0), false);
        }
        assertFalse(dog.overran(), "20 刻的一步给 100 刻");
        dog.observe(HERE, false);
        assertTrue(dog.overran());
    }

    @Test
    void aSlowDigGetsALongerDeadline() {
        Watchdog dog = new Watchdog();
        dog.begin(400, HERE);
        for (int i = 0; i < 800; i++) {
            dog.observe(HERE, i % 5 == 0);
        }
        assertFalse(dog.overran(), "挖一块硬方块的一步期限跟着挖掘的刻数长");
        assertTrue(dog.progressing());
    }
}
