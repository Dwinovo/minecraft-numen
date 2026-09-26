package com.dwinovo.numen.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出预算只有一份({@link Listing#MAX_LINES} 行或 {@link Listing#MAX_BYTES} 字节,先到哪个算哪个),动作自己列的清单与帮助
 * 同一种分页:同一个 {@code --page} 标志、同样按预算切页、同样的翻页提示与越界的说法。
 */
class ListingTest {

    /** 一条 1000 字节的行:一页放得下几十条,整张单子放不下。 */
    private static final String WIDE = "x".repeat(990);
    private static final int WIDE_ROWS = 120;
    private static final int SHORT_ROWS = 2500;

    @BeforeAll
    static void register() {
        door().registerCommands("gt_listing", "A group whose action lists things.", g -> {
            g.client("rows", "List the rows.", (src, args) -> src.reply(new Listing("Rows:", wideRows(),
                    "That is all.", "gt_listing rows").result(args).toJson()), Listing.PAGE)
                    .example("gt_listing rows --page 2");
            g.client("short", "List many short rows.", (src, args) -> {
                List<String> rows = new ArrayList<>();
                for (int i = 1; i <= SHORT_ROWS; i++) {
                    rows.add("  r" + i);
                }
                src.reply(new Listing("Short:", rows, "", "gt_listing short").result(args).toJson());
            }, Listing.PAGE).example("gt_listing short");
            g.client("one", "One entry bigger than a page.", (src, args) -> src.reply(new Listing("One:",
                    List.of("字".repeat(Listing.MAX_BYTES)), "", "gt_listing one").result(args).toJson()),
                    Listing.PAGE).example("gt_listing one");
        });
    }

    private static List<String> wideRows() {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= WIDE_ROWS; i++) {
            rows.add(String.format("  row %03d %s", i, WIDE));
        }
        return rows;
    }

    /** 按字节先到:放满预算就停,整条整条地放,末尾照 pi 的样子说一共几条、这是哪一段、下一段怎么取。 */
    @Test
    void aListOverTheByteBudgetKeepsItsHeadAndSaysHowToGetTheRest() {
        String first = onClient("gt_listing rows").message();
        int shown = shownTo(first, WIDE_ROWS, "gt_listing rows --page 2");
        assertTrue(first.startsWith("Rows:\n  row 001 "), first);
        assertTrue(first.endsWith("That is all."), "结尾一句照旧在最后");
        String content = first.substring(0, first.indexOf("\n[Showing")) + "\nThat is all.";
        assertTrue(bytes(content) <= Listing.MAX_BYTES, "内容不超预算: " + bytes(content));
        assertTrue(bytes(content) + 1 + bytes(wideRows().get(shown)) > Listing.MAX_BYTES, "再放一条就超了");
        assertFalse(first.contains(String.format("row %03d", shown + 1)), "下一条不在这一页");

        String second = onClient("gt_listing rows --page 2").message();
        assertTrue(second.startsWith("Rows:\n" + String.format("  row %03d", shown + 1)), "第二页从停下的那条接着");

        CliFixture.Outcome beyond = onClient("gt_listing rows --page 99");
        assertFalse(beyond.success());
        assertTrue(beyond.message().startsWith("no page 99; gt_listing rows has pages 1-"), beyond.message());
    }

    /** 按行先到:两千行一页。 */
    @Test
    void aListOverTheLineBudgetStopsAtTheLineLimit() {
        String first = onClient("gt_listing short").message();
        int shown = shownTo(first, SHORT_ROWS, "gt_listing short --page 2");
        assertEquals(Listing.MAX_LINES - 1, shown, "抬头占一行");
        assertEquals(Listing.MAX_LINES + 1, first.split("\n").length, "内容两千行,加上翻页那一句");

        String last = onClient("gt_listing short --page 2").message();
        assertTrue(last.startsWith("Short:\n  r" + Listing.MAX_LINES + "\n"), last.substring(0, 40));
        assertTrue(last.endsWith("\n  r" + SHORT_ROWS), "最后一页不再说翻页");
    }

    /** 一条条目自己就比一页大:单占一页,只放得下的开头(不切断一个字),注明它原来多大。 */
    @Test
    void anEntryBiggerThanAPageShowsItsHeadAndSaysSo() {
        String page = onClient("gt_listing one").message();
        int size = bytes("字".repeat(Listing.MAX_BYTES));
        assertTrue(page.contains("\n[This entry is " + size + " bytes; only its first "), page.substring(0, 20));
        String kept = page.substring("One:\n".length(), page.indexOf("\n[This entry"));
        assertTrue(kept.chars().allMatch(c -> c == '字'), "没切断一个字");
        assertTrue(bytes(kept) <= Listing.MAX_BYTES && bytes(kept) > Listing.MAX_BYTES - 3);
    }

    /** 没有抬头与结尾的一张(技能正文、札记正文按行分页):就是那些条目,不多出空行。 */
    @Test
    void aListingWithNoHeadOrFootIsJustItsEntries() {
        CommandArgs noPage = CommandArgs.fromJson(List.of(Listing.PAGE), new com.google.gson.JsonObject());
        assertEquals("a\nb", new Listing("", List.of("a", "b"), "", "gt_listing rows").result(noPage).message());
        assertEquals("", new Listing("", List.of(), "", "gt_listing rows").result(noPage).message());
    }

    @Test
    void thePageFlagReadsTheSameAsInHelp() {
        assertEquals("""
                gt_listing rows [--page <integer>]
                  List the rows.
                  --page <integer> (integer 1-99; optional) — Which page of the list.
                  Examples:
                    gt_listing rows --page 2""",
                onClient("gt_listing rows --help").message());
    }

    /** 这一页的翻页提示说到第几条,并且下一页的写法对;返回显示到第几条。 */
    static int shownTo(String page, int total, String next) {
        Matcher m = Pattern.compile("\n\\[Showing 1-(\\d+) of " + total + "\\. Use " + Pattern.quote(next)
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
