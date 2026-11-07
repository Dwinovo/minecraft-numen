package com.dwinovo.numen.api.client.ui.widget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 开合进度:按帧推进,走满为止,反向再走回来。 */
class RevealTest {

    @Test
    void opensOverTheTransitionNotInstantly() {
        Reveal r = new Reveal();
        r.setOpen(true);
        r.step(1000);
        assertEquals(0f, r.value(), "第一帧只记时间,还没走");
        assertTrue(r.step(1000 + Reveal.MS / 2));
        assertTrue(r.value() > 0f && r.value() < 1f, "走到一半");
        assertTrue(r.step(1000 + Reveal.MS * 2));
        assertEquals(1f, r.value());
        assertFalse(r.step(1000 + Reveal.MS * 3), "走满了就不再动");
    }

    @Test
    void closingWalksBackAndSnapSkipsTheTransition() {
        Reveal r = new Reveal();
        r.snap(true);
        assertEquals(1f, r.value());
        r.toggle();
        r.step(0);
        r.step(Reveal.MS * 5);
        assertEquals(0f, r.value());
        r.snap(true);
        assertEquals(1f, r.value(), "snap 不走过渡");
    }
}
