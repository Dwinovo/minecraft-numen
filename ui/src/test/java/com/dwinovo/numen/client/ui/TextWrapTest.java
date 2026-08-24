package com.dwinovo.numen.client.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 折行:拉丁按空格断、CJK 字符级硬断、行数上限省略号、空串与换行。 */
class TextWrapTest {

    /** 假度量:每字符 6px——宽度上限 60 即每行 10 字符。 */
    private static final ToIntFunction<String> W = s -> s.length() * 6;

    @Test
    void latinBreaksAtSpaces() {
        List<String> lines = TextWrap.wrap("check your api key now", 60, W, 0);
        assertEquals(List.of("check your", "api key", "now"), lines);
    }

    @Test
    void cjkBreaksPerCharacter() {
        List<String> lines = TextWrap.wrap("密钥无效请检查是否复制完整了", 60, W, 0);
        assertEquals(List.of("密钥无效请检查是否复", "制完整了"), lines);
        for (String l : lines) assertTrue(W.applyAsInt(l) <= 60);
    }

    @Test
    void overlongWordHardBreaks() {
        List<String> lines = TextWrap.wrap("sk-abcdefghijklmnop", 60, W, 0);
        assertEquals(2, lines.size());
        assertTrue(W.applyAsInt(lines.get(0)) <= 60);
    }

    @Test
    void maxLinesTruncatesWithEllipsis() {
        // 三行的量压进两行上限 → 末行截断加省略号。
        List<String> lines = TextWrap.wrap("一二三四五六七八九十".repeat(3), 60, W, 2);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).endsWith("..."));
        assertTrue(W.applyAsInt(lines.get(1)) <= 60);
    }

    @Test
    void exactlyFittingLinesNeedNoEllipsis() {
        List<String> lines = TextWrap.wrap("一二三四五六七八九十甲乙丙丁戊己庚辛壬癸", 60, W, 2);
        assertEquals(2, lines.size());
        assertFalse(lines.get(1).endsWith("..."));
    }

    @Test
    void newlinesStartFreshLines() {
        assertEquals(List.of("a", "b"), TextWrap.wrap("a\nb", 60, W, 0));
    }

    @Test
    void emptyTextYieldsNoLines() {
        assertTrue(TextWrap.wrap("", 60, W, 0).isEmpty());
        assertTrue(TextWrap.wrap(null, 60, W, 0).isEmpty());
    }

    @Test
    void everyLineRespectsMaxWidth() {
        for (String msg : new String[]{
                "mixed 中英 mixed 混排 text 文本 wrapping 折行 behaviour 行为",
                "shortword " + "长".repeat(40) + " tail"}) {
            for (String line : TextWrap.wrap(msg, 60, W, 0)) {
                assertTrue(W.applyAsInt(line) <= 60, "超宽: '" + line + "'");
            }
        }
    }
}
