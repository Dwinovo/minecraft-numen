package com.dwinovo.numen.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 缓动函数的边界与单调性——动画的正确性就是这几条数学性质。 */
class AnimationTest {

    @Test
    void easeOutCubicBoundsAndMonotonic() {
        assertEquals(0f, Animation.easeOutCubic(0f));
        assertEquals(1f, Animation.easeOutCubic(1f));
        assertEquals(0f, Animation.easeOutCubic(-1f));   // 越界夹紧
        assertEquals(1f, Animation.easeOutCubic(2f));
        float prev = 0f;
        for (float t = 0f; t <= 1f; t += 0.05f) {
            float v = Animation.easeOutCubic(t);
            assertTrue(v >= prev, "非单调 at " + t);
            prev = v;
        }
    }

    @Test
    void lerpSnapsWhenClose() {
        assertEquals(10f, Animation.lerpTo(9.99f, 10f, 0.2f, 0.05f));
        float mid = Animation.lerpTo(0f, 10f, 0.5f, 0.05f);
        assertEquals(5f, mid, 0.001f);
    }

    @Test
    void progressClampsAndHandlesZeroDuration() {
        assertEquals(0.5f, Animation.progress(100, 200), 0.001f);
        assertEquals(1f, Animation.progress(999, 200));
        assertEquals(1f, Animation.progress(50, 0));   // 零时长 = 立即完成
    }
}
