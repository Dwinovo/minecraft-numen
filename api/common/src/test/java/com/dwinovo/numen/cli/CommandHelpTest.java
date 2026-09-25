package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onClient;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帮助是模型读的界面:三层的样子逐字钉住,措辞一变这里就红。只有动作这一层给全(参数的取值提示、例子、注意、
 * 相关命令),组的帮助仍一行一个动作;列表超过一页时说还剩多少、怎么翻;系统提示的索引与根帮助是同一份条目。
 */
class CommandHelpTest {

    static final Param<Integer> X = Param.required("x", ArgType.integer(0, 100), "X coordinate.");
    static final Param<String> MODE = Param.optional("mode", ArgType.word(), "How to walk.")
            .values("walk or sprint")
            .whenOmitted("walk");
    static final Param<String> BODY = Param.required("body", ArgType.text(), "What to write.")
            .values("any text; the owner reads it as written");

    @BeforeAll
    static void register() {
        door().registerCommands("gt_help", "A group the tests read.", g -> {
            g.server("walk", "Walk somewhere.", (src, args) -> src.reply(TaskResult.ok("walked").toJson()), X, MODE)
                    .example("numen gt_help walk 12")
                    .example("numen gt_help walk 12 --mode sprint")
                    .note("Background work: the result arrives as a task_finished event.")
                    .note("Does not ask your owner.")
                    .seeAlso("numen gt_help note")
                    .promote("gt_help_walk", "Walk somewhere, as a tool.");
            g.client("note", "Write a note.", (src, args) -> src.reply(TaskResult.ok("noted").toJson()), BODY)
                    .example("numen gt_help note buy more torches");
        });
        door().registerCommands("gt_many", "A group with a long list.", g -> {
            for (int i = 1; i <= 25; i++) {
                String name = String.format("act%02d", i);
                g.server(name, "Action number " + i + ".",
                        (src, args) -> src.reply(TaskResult.ok("done").toJson()))
                        .example("numen gt_many " + name);
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
        assertEquals(GROUP_HELP, help.message(), "组的帮助一行一个动作,例子、注意、相关命令都不进来");
    }

    @Test
    void anActionGivesEverything() {
        assertEquals("""
                numen gt_help walk <x> [--mode <word>]
                  Walk somewhere.
                  <x> (integer 0-100) — X coordinate.
                  --mode <word> (word; optional) — How to walk. Values: walk or sprint. Omit to walk.
                  Examples:
                    numen gt_help walk 12
                    numen gt_help walk 12 --mode sprint
                  Notes:
                    Background work: the result arrives as a task_finished event.
                    Does not ask your owner.
                  See also: numen gt_help note
                  Shortcut tool: gt_help_walk.""", onClient("numen gt_help walk --help").message());
        assertEquals("""
                numen gt_help note <body...>
                  Write a note.
                  <body...> (text, the rest of the line) — What to write. Values: any text; the owner reads it \
                as written.
                  Examples:
                    numen gt_help note buy more torches""",
                onClient("numen gt_help note --help").message(), "没有注意与相关命令时那两块不出现");
    }

    @Test
    void theShortcutSchemaReadsTheSameValueHintsAsTheHelp() {
        assertEquals(Schema.object()
                .integer("x", "X coordinate.", 0, 100)
                .optionalString("mode", "How to walk. Values: walk or sprint. Omit to walk.")
                .build(), ToolRegistry.get("gt_help_walk").parameterSchema());
    }

    @Test
    void theValueHintsAreCheckedWhenDeclared() {
        assertThrows(IllegalArgumentException.class, () -> Param.required("x", ArgType.word(), "X.").whenOmitted("y"),
                "必填参数没有\"不写时\"");
        assertThrows(IllegalArgumentException.class, () -> Param.optional("x", ArgType.word(), "X.").whenOmitted(" "));
        assertThrows(IllegalArgumentException.class, () -> Param.optional("x", ArgType.word(), "X.").values(""));
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
        assertTrue(index.startsWith("<commands>\nNumen's command groups, run with the command tool "
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
