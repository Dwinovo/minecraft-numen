package com.dwinovo.numen.client.ui;

import org.junit.jupiter.api.Test;

import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 折行几何:硬行、软行(空格优先/逐字)、下标↔行、横坐标↔下标。假度量每字符 6px。 */
class WrappedTextTest {

    private static final ToIntFunction<String> W = t -> t.length() * 6;

    @Test
    void newlinesAreHardLinesAndEmptyTextIsOneLine() {
        assertEquals(1, WrappedText.of("", 60, W).lineCount());
        WrappedText t = WrappedText.of("ab\n\ncd", 60, W);
        assertEquals(3, t.lineCount());
        assertEquals("ab", t.line(0));
        assertEquals("", t.line(1));
        assertEquals("cd", t.line(2));
        assertTrue(t.endsHard(0));
        assertFalse(t.endsSoft(0));
    }

    @Test
    void longRunBreaksPerCharacterAndWordsBreakAtSpaces() {
        WrappedText run = WrappedText.of("aaaaaaaaaaaa", 54, W);   // 9 字一行
        assertEquals("aaaaaaaaa", run.line(0));
        assertEquals("aaa", run.line(1));
        assertTrue(run.endsSoft(0));
        WrappedText words = WrappedText.of("aaaa bbbb cccc", 54, W);
        assertEquals("aaaa bbbb ", words.line(0), "断行的空格留在行里,下标对得上");
        assertEquals("cccc", words.line(1));
    }

    @Test
    void cursorOnSoftBoundaryBelongsToNextLine() {
        WrappedText t = WrappedText.of("aaaaaaaaaaaa", 54, W);
        assertEquals(1, t.lineOf(9), "软换行边界归下一行行首");
        assertEquals(0, t.lineOf(8));
        assertEquals(1, t.lineOf(12), "末尾在最后一行");
        WrappedText hard = WrappedText.of("ab\ncd", 54, W);
        assertEquals(0, hard.lineOf(2), "硬换行前的位置还在本行");
        assertEquals(1, hard.lineOf(3));
    }

    @Test
    void xAndIndexRoundTrip() {
        WrappedText t = WrappedText.of("abc\ndefgh", 54, W);
        assertEquals(12, t.xOf(6), "d e 两个字之后");
        assertEquals(6, t.indexAtX(1, 12));
        assertEquals(6, t.indexAtX(1, 14), "落在字符前半归左");
        assertEquals(7, t.indexAtX(1, 15), "后半归右");
        assertEquals(3, t.indexAtX(0, 500), "行外夹到行尾");
    }

    @Test
    void withoutMeasureOnlyHardLinesAreCut() {
        WrappedText t = WrappedText.of("aaaaaaaaaaaa\nb", 54, null);
        assertEquals(2, t.lineCount());
        assertEquals(0, t.widthOf(0, 5));
        assertEquals(12, t.indexAtX(0, 30), "量不了就落在行尾");
    }
}
