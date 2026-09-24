package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onClient;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帮助是模型读的界面:三层的样子逐字钉住,措辞一变这里就红。列表超过一页时说还剩多少、怎么翻;
 * 系统提示的索引与根帮助是同一份条目。
 */
class CommandHelpTest {

    static final Param<Integer> X = Param.required("x", ArgType.integer(0, 100), "X coordinate.");
    static final Param<String> MODE = Param.optional("mode", ArgType.word(), "How to walk.");
    static final Param<String> BODY = Param.required("body", ArgType.text(), "What to write.");

    @BeforeAll
    static void register() {
        door().registerCommands("gt_help", "A group the tests read.", g -> {
            g.server("walk", "Walk somewhere.", (src, args) -> src.reply(TaskResult.ok("walked").toJson()), X, MODE)
                    .promote("gt_help_walk", "Walk somewhere, as a tool.");
            g.client("note", "Write a note.", (src, args) -> src.reply(TaskResult.ok("noted").toJson()), BODY);
        });
        door().registerCommands("gt_many", "A group with a long list.", g -> {
            for (int i = 1; i <= 25; i++) {
                g.server(String.format("act%02d", i), "Action number " + i + ".",
                        (src, args) -> src.reply(TaskResult.ok("done").toJson()));
            }
        });
    }

    private static final String GROUP_HELP = """
            numen gt_help: A group the tests read. Actions:
              numen gt_help walk <x> [--mode <word>] — Walk somewhere.
              numen gt_help note <body...> — Write a note.
            numen gt_help <action> --help explains one action.""";

    @Test
    void aGroupListsItsActionsWithUsageAndOneSentenceEach() {
        CliFixture.Outcome help = onClient("numen gt_help --help");
        assertTrue(help.success());
        assertEquals(GROUP_HELP, help.message());
    }

    @Test
    void anActionExplainsEachArgumentAndNamesItsShortcut() {
        assertEquals("""
                numen gt_help walk <x> [--mode <word>]
                Walk somewhere.
                  <x> (integer 0-100) — X coordinate.
                  --mode <word> (word; optional) — How to walk.
                Shortcut tool: gt_help_walk.""", onClient("numen gt_help walk --help").message());
        assertEquals("""
                numen gt_help note <body...>
                Write a note.
                  <body...> (text, the rest of the line) — What to write.""",
                onClient("numen gt_help note --help").message());
    }

    @Test
    void helpIsAnsweredOnWhicheverSideReadsIt() {
        CliFixture.Outcome server = onServer("numen gt_help --help");
        assertEquals(GROUP_HELP, server.message(), "服务端解析同一棵树,帮助一字不差");
        assertFalse(onClient("numen gt_help walk --help").forwarded, "帮助当场回,不跑服务端");
    }

    @Test
    void theRootListsEveryGroupInOneSentenceAndTheIndexCarriesTheSameLines() {
        for (String line : new String[]{"numen help", "numen --help"}) {
            String root = onClient(line).message();
            assertTrue(root.startsWith("numen <group> <action> [arguments]. Command groups:\n"), root);
            assertTrue(root.contains("\n  gt_help — A group the tests read.\n"), root);
            assertTrue(root.endsWith("\nnumen <group> --help lists a group's actions."), root);
        }
        String index = NumenCli.index();
        assertTrue(index.startsWith("<commands>\nCommand groups of the numen tool "
                + "(numen <group> --help lists a group's actions):\n"), index);
        assertTrue(index.contains("\ngt_help — A group the tests read.\n"), index);
        assertTrue(index.indexOf("gt_help —") < index.indexOf("gt_many —"), "按名字排序: " + index);
        assertTrue(index.endsWith("\n</commands>"), index);
        assertEquals(index, NumenCli.index(), "字节稳定");
    }

    @Test
    void aLongListIsPagedAndSaysHowToTurnThePage() {
        String first = onClient("numen gt_many --help").message();
        assertTrue(first.startsWith("numen gt_many: A group with a long list. Actions:\n"
                + "  numen gt_many act01 — Action number 1.\n"), first);
        assertTrue(first.contains("  numen gt_many act20 — Action number 20.\n"
                + "(page 1 of 2, 5 more: numen gt_many --help --page 2)\n"
                + "numen gt_many <action> --help explains one action."), first);
        assertFalse(first.contains("act21"), first);

        String second = onClient("numen gt_many --help --page 2").message();
        assertEquals("""
                numen gt_many: A group with a long list. Actions:
                  numen gt_many act21 — Action number 21.
                  numen gt_many act22 — Action number 22.
                  numen gt_many act23 — Action number 23.
                  numen gt_many act24 — Action number 24.
                  numen gt_many act25 — Action number 25.
                numen gt_many <action> --help explains one action.""", second);

        CliFixture.Outcome beyond = onClient("numen gt_many --help --page 3");
        assertFalse(beyond.success());
        assertTrue(beyond.message().startsWith("no page 3; numen gt_many --help has pages 1-2"), beyond.message());
    }
}
