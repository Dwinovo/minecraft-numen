package com.dwinovo.numen.api.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 颈部模型:到得了、不冲过、速度与加速度不超上限、两轴同时到位、走近的那一边。 */
class NeckTest {

    private static Neck turn(Neck from, float yaw, float pitch, int ticks) {
        Neck n = from;
        for (int i = 0; i < ticks; i++) {
            n = n.toward(yaw, pitch);
        }
        return n;
    }

    /** 转 90 度:不冲过目标,二十刻以内到位并停住。 */
    @Test
    void aBigTurnArrivesWithoutOvershooting() {
        Neck n = Neck.at(0, 0);
        float most = 0;
        for (int i = 0; i < 40; i++) {
            n = n.toward(90, 0);
            most = Math.max(most, n.yaw());
        }
        assertTrue(most <= 90.0F + 1.0E-3F, "冲过了:" + most);
        assertEquals(90, n.yaw(), 1.0E-3);
        assertEquals(0, n.yawSpeed(), 1.0E-3);
        assertTrue(turn(Neck.at(0, 0), 90, 0, 20).yaw() > 89.9F, "二十刻没转完");
    }

    /** 角速度、角加速度不超上限;起转是软的,第一刻只转一个加速度。 */
    @Test
    void speedAndAccelerationStayWithinTheirLimits() {
        Neck n = Neck.at(0, 0);
        for (int i = 0; i < 40; i++) {
            Neck next = n.toward(170, -60);
            double speed = Math.hypot(next.yawSpeed(), next.pitchSpeed());
            double accel = Math.hypot(next.yawSpeed() - n.yawSpeed(), next.pitchSpeed() - n.pitchSpeed());
            assertTrue(speed <= Neck.MAX_SPEED + 1.0E-3, "速度 " + speed);
            assertTrue(accel <= Neck.MAX_ACCEL + 1.0E-3, "加速度 " + accel);
            if (i == 0) {
                assertEquals(Neck.MAX_ACCEL, speed, 1.0E-3);
            }
            n = next;
        }
    }

    /** 两轴同时到位:偏航差 90、俯仰差 30,转的过程里两者的比例不变。 */
    @Test
    void bothAxesArriveTogether() {
        Neck n = Neck.at(0, 0);
        for (int i = 0; i < 6; i++) {
            n = n.toward(90, 30);
            assertEquals(3.0, n.yaw() / n.pitch(), 0.05);
        }
        n = turn(n, 90, 30, 40);
        assertEquals(90, n.yaw(), 1.0E-3);
        assertEquals(30, n.pitch(), 1.0E-3);
    }

    /** 偏航走近的那一边:从 170 度转到 -170 度是往正方向转 20 度。 */
    @Test
    void yawTurnsTheShortWay() {
        Neck n = turn(Neck.at(170, 0), -170, 0, 30);
        assertEquals(190, n.yaw(), 1.0E-3);
    }
}
