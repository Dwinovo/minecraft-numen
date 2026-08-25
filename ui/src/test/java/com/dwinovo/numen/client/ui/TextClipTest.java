package com.dwinovo.numen.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 单行收口:码点为单位,不劈代理对;窄到装不下省略号给空串;无约束原样。 */
class TextClipTest {

    /** 假度量:每 char 6px(刻意按 char 而非码点——正好暴露"劈开代理对"类错误)。 */
    private static final IDrawSurface S = new IDrawSurface() {
        @Override public void fillRect(int x, int y, int w, int h, int argb) { }
        @Override public void drawText(String t, int x, int y, int argb, boolean shadow) { }
        @Override public int textWidth(String t) { return t.length() * 6; }
        @Override public int lineHeight() { return 9; }
        @Override public void pushScissor(int x, int y, int w, int h) { }
        @Override public void popScissor() { }
    };

    @Test
    void fitsUnchanged() {
        assertEquals("hello", TextClip.fit(S, "hello", 30));
    }

    @Test
    void clipsWithEllipsis() {
        // 30px 里要装"前缀+…(6px)":前缀最多 4 个 char
        assertEquals("abcd…", TextClip.fit(S, "abcdefgh", 30));
    }

    @Test
    void neverSplitsSurrogatePairs() {
        // 🌲 是一对代理(2 char = 12px)。18px 减省略号剩 12px:够 aa(12px),
        // 🌲 塞不下且不能塞半个——按 char 截会正好切在代理对中间
        String clipped = TextClip.fit(S, "aa🌲bb", 18);
        assertEquals("aa…", clipped);
        assertFalse(clipped.chars().anyMatch(ch -> Character.isSurrogate((char) ch)), "不许出现孤代理");
    }

    @Test
    void keepsWholeCodePointWhenItFits() {
        // 24px - 省略号 6px = 18px:aa(12) + 🌲(12) 超,aa 止步;但 30px 时 🌲 整个进来
        assertEquals("aa🌲…", TextClip.fit(S, "aa🌲bbcc", 30));
    }

    @Test
    void tooNarrowForEllipsisGivesEmpty() {
        assertEquals("", TextClip.fit(S, "hello", 5));
    }

    @Test
    void noConstraintPassesThrough() {
        // w<=0 = 没量过尺寸的控件:保持旧行为,不无声吞字
        assertEquals("长得没边的一串字", TextClip.fit(S, "长得没边的一串字", 0));
        assertEquals("", TextClip.fit(S, null, 100));
    }

    @Test
    void exactBoundaryIsNotClipped() {
        assertTrue(TextClip.fit(S, "abcde", 30).equals("abcde"), "恰好等宽不该动");
    }
}
