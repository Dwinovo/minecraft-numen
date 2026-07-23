package com.dwinovo.numen.core.pathing.execute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视角步进量化的数学钉桩:像素折算往返、一 tick 收敛到半像素以内、
 * 残差处停步、pitch 回正与钳制、yaw 差不归一直接累进、输入帧变换。
 */
class AimProcessorTest {

    private static final float EPS = 1e-4f;

    /** 默认灵敏度 0.5 下,一像素恰为 0.15°。 */
    @Test
    void onePixelIsPointFifteenDegrees() {
        AimProcessor aim = new AimProcessor();
        assertEquals(0.15f, aim.mouseToAngle(1), EPS);
    }

    /** 角度 → 像素 → 角度往返:整像素角度无损。 */
    @Test
    void roundTripOnPixelGrid() {
        AimProcessor aim = new AimProcessor();
        for (int px = -300; px <= 300; px += 7) {
            float angle = aim.mouseToAngle(px);
            assertEquals(px, aim.angleToMouse(angle), 0.0);
            assertEquals(angle, aim.stepAngle(angle), EPS);
        }
    }

    /** 大角度差一 tick 步进后,残差在半像素以内。 */
    @Test
    void largeDeltaConvergesWithinHalfPixelInOneTick() {
        AimProcessor aim = new AimProcessor();
        float halfPixel = aim.mouseToAngle(1) / 2;
        for (float delta : new float[]{179.9f, 90.0f, 37.37f, -0.5f, -123.456f}) {
            float stepped = aim.stepAngle(delta);
            assertTrue(Math.abs(delta - stepped) <= halfPixel + EPS,
                    "delta=" + delta + " stepped=" + stepped);
        }
    }

    /** 残差小于半像素时像素数取整为 0,视角停在原地(不再抖动)。 */
    @Test
    void subHalfPixelResidualStops() {
        AimProcessor aim = new AimProcessor();
        float halfPixel = aim.mouseToAngle(1) / 2;
        assertEquals(0.0f, aim.stepAngle(halfPixel * 0.9f), 0.0f);
        assertEquals(0.0f, aim.stepAngle(-halfPixel * 0.9f), 0.0f);
    }

    /** 多 tick 迭代:从任意角度反复步进,单调逼近并稳定在半像素以内。 */
    @Test
    void iterativeSteppingConverges() {
        AimProcessor aim = new AimProcessor();
        float halfPixel = aim.mouseToAngle(1) / 2;
        float current = -170.0f;
        float target = 155.0f;
        for (int tick = 0; tick < 5; tick++) {
            AimProcessor.Rotation r = aim.step(current, 0, target, 30.0f);
            current = r.yaw();
        }
        assertTrue(Math.abs(AimProcessor.normalizeDelta(target - current)) <= halfPixel + EPS);
    }

    /**
     * yaw 差不归一:直接用原始差值累进,视角字段允许累积出界。
     * -170° 到 170° 的原始差是 +340°,步进后向正向落(长弧),而不是
     * 归一后的 -20° 短弧。角度比较处仍走短弧({@link #normalizeDelta})。
     */
    @Test
    void yawDeltaNotNormalized() {
        assertEquals(-20.0f, AimProcessor.normalizeDelta(170.0f - (-170.0f)), EPS);
        AimProcessor aim = new AimProcessor();
        AimProcessor.Rotation r = aim.step(-170.0f, 0, 170.0f, 30.0f);
        // 原始差 +340° → 步进后向正向落(长弧),不绕短弧
        assertTrue(r.yaw() > -170.0f);
    }

    /** pitch 未指定(与当前相等)时向 [-20,10] 每 tick 回正 1°。 */
    @Test
    void pitchNudgesToLevelWhenUnspecified() {
        assertEquals(-39.0f, AimProcessor.nudgeToLevel(-40.0f), EPS);
        assertEquals(89.0f, AimProcessor.nudgeToLevel(90.0f), EPS);
        assertEquals(-20.0f, AimProcessor.nudgeToLevel(-20.0f), EPS);
        assertEquals(10.0f, AimProcessor.nudgeToLevel(10.0f), EPS);
        assertEquals(0.0f, AimProcessor.nudgeToLevel(0.0f), EPS);

        AimProcessor aim = new AimProcessor();
        // 目标 pitch == 当前 pitch(90°,抬头看天残留)→ 回正一步
        AimProcessor.Rotation r = aim.step(0, 90.0f, 45.0f, 90.0f);
        assertTrue(r.pitch() < 90.0f);
    }

    /** pitch 指定时不回正,直接步进到目标。 */
    @Test
    void pitchStepsWhenSpecified() {
        AimProcessor aim = new AimProcessor();
        AimProcessor.Rotation r = aim.step(0, 0, 0, 90.0f);
        assertEquals(90.0f, r.pitch(), aim.mouseToAngle(1));
    }

    /** pitch 钳制在 [-90, 90]。 */
    @Test
    void pitchClamped() {
        assertEquals(90.0f, AimProcessor.clampPitch(120.0f), EPS);
        assertEquals(-90.0f, AimProcessor.clampPitch(-95.0f), EPS);
    }

    /** 输入帧变换:两帧一致时原样返回。 */
    @Test
    void remapIdentityWhenFramesAgree() {
        float[] v = AimProcessor.remapInput(0.0f, 1.0f, 45.0f, 45.0f);
        assertEquals(0.0f, v[0], EPS);
        assertEquals(1.0f, v[1], EPS);
    }

    /**
     * 输入帧变换:目标 yaw 领先实际 90° 时,目标帧的"前进"要落成
     * 实际帧的"右横移"才能产生同一世界方向。
     */
    @Test
    void remapForwardBecomesStrafeAtNinetyDegrees() {
        float[] v = AimProcessor.remapInput(0.0f, 1.0f, 90.0f, 0.0f);
        assertEquals(-1.0f, v[0], EPS); // xxa 负 = 向右横移
        assertEquals(0.0f, v[1], EPS);
    }

    /** 输入帧变换保持模长(冲量幅度不因帧变换放大或缩小)。 */
    @Test
    void remapPreservesMagnitude() {
        float[] v = AimProcessor.remapInput(0.3f, 0.7f, 123.0f, -37.0f);
        double before = Math.sqrt(0.3 * 0.3 + 0.7 * 0.7);
        double after = Math.sqrt(v[0] * v[0] + v[1] * v[1]);
        assertEquals(before, after, 1e-4);
    }
}
