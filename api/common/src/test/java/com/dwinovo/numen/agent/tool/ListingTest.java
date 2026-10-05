package com.dwinovo.numen.agent.tool;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出预算照一份({@link Listing#MAX_LINES} 行或 {@link Listing#MAX_BYTES} 字节,先到哪个算哪个):整条整条地放,末尾说一共几条、这是
 * 哪一段、下一段怎么取;一条自己就比一页大时单占一页、只放开头、注明原来多大。
 */
class ListingTest {

    /** 一条 1000 字节的行:一页放得下几十条,整张单子放不下。 */
    private static final String WIDE = "x".repeat(990);
    private static final int WIDE_ROWS = 120;
    private static final int SHORT_ROWS = 2500;

    private static List<String> wideRows() {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= WIDE_ROWS; i++) {
            rows.add(String.format("row %03d %s", i, WIDE));
        }
        return rows;
    }

    /** 按字节先到:放满预算就停,下一页从停下的那条接着。 */
    @Test
    void aTextOverTheByteBudgetKeepsItsHeadAndSaysHowToGetTheRest() {
        Listing listing = new Listing(wideRows());
        String first = listing.page(1);
        int shown = shownTo(first, WIDE_ROWS, 2);
        String content = first.substring(0, first.indexOf("\n[Showing"));
        assertTrue(bytes(content) <= Listing.MAX_BYTES, "内容不超预算: " + bytes(content));
        assertTrue(bytes(content) + 1 + bytes(wideRows().get(shown)) > Listing.MAX_BYTES, "再放一条就超了");
        assertFalse(first.contains(String.format("row %03d", shown + 1)), "下一条不在这一页");
        assertTrue(listing.page(2).startsWith(String.format("row %03d", shown + 1)), "第二页从停下的那条接着");
    }

    /** 按行先到:两千行一页,最后一页不再说翻页。 */
    @Test
    void aTextOverTheLineBudgetStopsAtTheLineLimit() {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= SHORT_ROWS; i++) {
            rows.add("r" + i);
        }
        Listing listing = new Listing(rows);
        assertEquals(Listing.MAX_LINES, shownTo(listing.page(1), SHORT_ROWS, 2));
        String last = listing.page(2);
        assertTrue(last.startsWith("r" + (Listing.MAX_LINES + 1) + "\n"), last.substring(0, 20));
        assertTrue(last.endsWith("\nr" + SHORT_ROWS), "最后一页不再说翻页");
    }

    /** 一条条目自己就比一页大:单占一页,只放得下的开头(不切断一个字),注明它原来多大。 */
    @Test
    void anEntryBiggerThanAPageShowsItsHeadAndSaysSo() {
        String page = new Listing(List.of("字".repeat(Listing.MAX_BYTES))).page(1);
        int size = bytes("字".repeat(Listing.MAX_BYTES));
        assertTrue(page.contains("\n[This entry is " + size + " bytes; only its first "), page.substring(0, 20));
        String kept = page.substring(0, page.indexOf("\n[This entry"));
        assertTrue(kept.chars().allMatch(c -> c == '字'), "没切断一个字");
        assertTrue(bytes(kept) <= Listing.MAX_BYTES && bytes(kept) > Listing.MAX_BYTES - 3);
    }

    @Test
    void aPageThatIsNotThereSaysWhichThereAre() {
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> new Listing(wideRows()).page(99));
        assertTrue(none.getMessage().startsWith("no page 99; this text has pages 1-"), none.getMessage());
        assertEquals("", new Listing(List.of()).page(1));
        assertEquals("a\nb", new Listing(List.of("a", "b")).page(1));
    }

    /** 这一页的翻页提示说到第几条,并且下一页的写法对;返回显示到第几条。 */
    private static int shownTo(String page, int total, int next) {
        Matcher m = Pattern.compile("\n\\[Showing 1-(\\d+) of " + total + "\\. Call it again with page = " + next
                + " to continue\\.]").matcher(page);
        assertTrue(m.find(), "没有翻页提示: " + page.substring(Math.max(0, page.length() - 200)));
        int shown = Integer.parseInt(m.group(1));
        assertTrue(shown >= 1 && shown < total, "显示到第 " + shown + " 条");
        return shown;
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
