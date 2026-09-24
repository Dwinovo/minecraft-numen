package com.dwinovo.numen.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动作自己列的清单与帮助同一种分页:同一个 {@code --page} 标志、同样每页 20 行、同样的翻页提示与越界的说法。
 */
class ListingTest {

    @BeforeAll
    static void register() {
        door().registerCommands("gt_listing", "A group whose action lists things.", g ->
                g.client("rows", "List the rows.", (src, args) -> {
                    List<String> rows = new ArrayList<>();
                    for (int i = 1; i <= 23; i++) {
                        rows.add("  row " + i);
                    }
                    src.reply(new Listing("Rows (23):", rows, "That is all.", "numen gt_listing rows")
                            .result(args).toJson());
                }, Listing.PAGE).example("numen gt_listing rows --page 2"));
    }

    @Test
    void anActionsListIsPagedLikeHelp() {
        String first = onClient("numen gt_listing rows").message();
        assertTrue(first.startsWith("Rows (23):\n  row 1\n"), first);
        assertTrue(first.endsWith("  row 20\n(page 1 of 2, 3 more: numen gt_listing rows --page 2)\nThat is all."),
                first);

        assertEquals("""
                Rows (23):
                  row 21
                  row 22
                  row 23
                That is all.""", onClient("numen gt_listing rows --page 2").message());

        CliFixture.Outcome beyond = onClient("numen gt_listing rows --page 3");
        assertFalse(beyond.success());
        assertEquals("no page 3; numen gt_listing rows has pages 1-2", beyond.message());
    }

    @Test
    void thePageFlagReadsTheSameAsInHelp() {
        assertEquals("""
                numen gt_listing rows [--page <integer>]
                  List the rows.
                  --page <integer> (integer 1-99; optional) — Which page of the list.
                  Examples:
                    numen gt_listing rows --page 2""",
                onClient("numen gt_listing rows --help").message());
    }
}
