package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.KeyCodes;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多行输入:软换行(空格优先/逐字)、光标四向与目标列、选区(Shift/拖选)、
 * 剪贴板(粘贴保留换行)、跨行删改、滚动夹紧与光标可见。
 * 假画布每字符 6px、行高 9(pitch=11);字段宽 67 → 内宽 56 → 每行 9 字。
 */
class MultilineTextFieldTest {

    private static final int SHIFT = 0x1;
    private static final int CTRL = KeyCodes.MOD_CTRL;

    private final WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
    private final UiRoot root = new UiRoot();
    private final AtomicReference<String> clip = new AtomicReference<>("");

    private MultilineTextField field(String initial) {
        root.setClipboard(clip::get, clip::set);
        MultilineTextField f = root.add(new MultilineTextField(initial, v -> { }));
        f.setBounds(0, 0, 67, 50);
        f.render(s, WidgetTestSupport.C, -10, -10, 0);   // 首渲注入度量,换行几何就绪
        s.reset();
        return f;
    }

    @Test
    void typingAndEnterBuildMultilineValue() {
        MultilineTextField f = field("");
        f.charTyped('a');
        f.charTyped('b');
        f.keyPressed(KeyCodes.ENTER, 0);
        f.charTyped('c');
        assertEquals("ab\nc", f.value());
    }

    @Test
    void longRunWrapsAtCharacterBoundary() {
        MultilineTextField f = field("aaaaaaaaaaaa");   // 12 字无空格 → 9+3
        f.render(s, WidgetTestSupport.C, -10, -10, 0);
        assertTrue(s.texts.contains("aaaaaaaaa"), "首行 9 字: " + s.texts);
        assertTrue(s.texts.contains("aaa"), "次行 3 字: " + s.texts);
    }

    @Test
    void wrapPrefersSpaceBoundary() {
        MultilineTextField f = field("aaaa bbbb cccc");   // 压线的空格随行吞掉
        f.render(s, WidgetTestSupport.C, -10, -10, 0);
        assertTrue(s.texts.contains("aaaa bbbb "), "词不劈两半: " + s.texts);
        assertTrue(s.texts.contains("cccc"), s.texts.toString());
    }

    @Test
    void verticalMoveKeepsGoalColumnAcrossShortLine() {
        MultilineTextField f = field("abcdefghi\nab\nabcdefghi");
        f.keyPressed(KeyCodes.HOME, CTRL);
        for (int i = 0; i < 8; i++) f.keyPressed(KeyCodes.RIGHT, 0);   // 行0列8
        f.keyPressed(KeyCodes.DOWN, 0);
        assertEquals(12, f.cursor(), "短行夹到行尾");
        f.keyPressed(KeyCodes.DOWN, 0);
        assertEquals(21, f.cursor(), "目标列记忆:回到列 8");
    }

    @Test
    void homeEndWorkPerLineAndCtrlJumpsDocument() {
        MultilineTextField f = field("abc\ndef");
        f.keyPressed(KeyCodes.HOME, CTRL);
        assertEquals(0, f.cursor());
        f.keyPressed(KeyCodes.DOWN, 0);
        f.keyPressed(KeyCodes.END, 0);
        assertEquals(7, f.cursor());
        f.keyPressed(KeyCodes.HOME, 0);
        assertEquals(4, f.cursor());
        f.keyPressed(KeyCodes.END, CTRL);
        assertEquals(7, f.cursor());
    }

    @Test
    void shiftArrowsSelectAndTypingReplaces() {
        MultilineTextField f = field("abcdef");
        f.keyPressed(KeyCodes.HOME, CTRL);
        for (int i = 0; i < 3; i++) f.keyPressed(KeyCodes.RIGHT, SHIFT);
        assertEquals("abc", f.selectedText());
        f.charTyped('x');
        assertEquals("xdef", f.value());
        assertEquals(1, f.cursor());
    }

    @Test
    void arrowWithoutShiftCollapsesSelectionToEdge() {
        MultilineTextField f = field("abcdef");
        f.keyPressed(KeyCodes.HOME, CTRL);
        for (int i = 0; i < 4; i++) f.keyPressed(KeyCodes.RIGHT, SHIFT);
        f.keyPressed(KeyCodes.LEFT, 0);   // 收起到选区左缘,不再左移
        assertEquals(0, f.cursor());
        assertEquals("", f.selectedText());
    }

    @Test
    void clipboardRoundTripPreservesNewlines() {
        MultilineTextField f = field("ab\ncd");
        f.keyPressed(KeyCodes.KEY_A, CTRL);
        f.keyPressed(KeyCodes.KEY_C, CTRL);
        assertEquals("ab\ncd", clip.get());
        clip.set("x\r\ny\rz");
        f.keyPressed(KeyCodes.KEY_V, CTRL);   // 全选态粘贴 = 整体替换,换行归一保留
        assertEquals("x\ny\nz", f.value());
    }

    @Test
    void cutRemovesSelectionIntoClipboard() {
        MultilineTextField f = field("abcdef");
        f.keyPressed(KeyCodes.HOME, CTRL);
        for (int i = 0; i < 2; i++) f.keyPressed(KeyCodes.RIGHT, SHIFT);
        f.keyPressed(KeyCodes.KEY_X, CTRL);
        assertEquals("ab", clip.get());
        assertEquals("cdef", f.value());
    }

    @Test
    void backspaceAcrossNewlineJoinsLines() {
        MultilineTextField f = field("ab\ncd");
        f.keyPressed(KeyCodes.HOME, CTRL);
        f.keyPressed(KeyCodes.DOWN, 0);
        f.keyPressed(KeyCodes.HOME, 0);
        f.keyPressed(KeyCodes.BACKSPACE, 0);
        assertEquals("abcd", f.value());
        assertEquals(2, f.cursor());
    }

    @Test
    void clickPositionsCursorAndDragSelects() {
        MultilineTextField f = field("abcdef\nghijkl");
        assertTrue(f.mouseClicked(4 + 13, 2 + 11 + 1, 0));   // 行1列2 前半/后半判定
        assertEquals(9, f.cursor());
        f.mouseDragged(4 + 25, 2 + 11 + 1, 0, 0);            // 拖到行1列4
        assertEquals("ij", f.selectedText());
        assertTrue(f.mouseReleased(4 + 25, 2 + 11 + 1, 0));
    }

    @Test
    void scrollClampsAndCursorStaysVisible() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append("line").append(i).append('\n');
        MultilineTextField f = field(sb.toString());          // 13 行 > 视口 4 行
        f.keyPressed(KeyCodes.HOME, CTRL);
        f.render(s, WidgetTestSupport.C, -10, -10, 0);
        assertFalse(s.texts.contains("line11"), "顶端时末行不可见");
        f.keyPressed(KeyCodes.END, CTRL);                     // 光标到底 → 自动滚到底
        s.reset();
        f.render(s, WidgetTestSupport.C, -10, -10, 0);
        assertTrue(s.texts.contains("line11"), "光标可见性驱动滚动: " + s.texts);
        assertTrue(f.mouseScrolled(5, 5, 1e9));               // 猛滚向上:夹紧到顶
        s.reset();
        f.render(s, WidgetTestSupport.C, -10, -10, 0);
        assertTrue(s.texts.contains("line0"), "夹紧后回到顶端: " + s.texts);
    }

    @Test
    void maxLengthTruncatesInsertions() {
        MultilineTextField f = field("").maxLength(4);
        clip.set("abcdef");
        f.keyPressed(KeyCodes.KEY_V, CTRL);
        assertEquals("abcd", f.value());
        f.charTyped('x');   // 已满:丢弃
        assertEquals("abcd", f.value());
    }

    @Test
    void placeholderShowsOnlyWhenEmptyAndUnfocused() {
        MultilineTextField f = field("").placeholder("写点什么…");
        f.render(s, WidgetTestSupport.C, -10, -10, 0);
        assertTrue(s.texts.contains("写点什么…"));
    }
}
