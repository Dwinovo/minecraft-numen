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
    static final Param<Boolean> FAST = Param.optional("fast", ArgType.bool(), "Run.");
    static final Param<Boolean> WET = Param.optional("wet", ArgType.bool(), "Wade through water.")
            .group("path flags");
    static final Param<Boolean> DRY = Param.optional("dry", ArgType.bool(), "Stay out of water.")
            .whenOmitted("wade when it is shorter")
            .group("path flags");
    static final Param<Integer> LATE = Param.optional("late", ArgType.integer(0, 9), "How late may she be.")
            .group("time flags");

    @BeforeAll
    static void register() {
        door().registerCommands("gt_help", "A group the tests read.", g -> {
            g.server("walk", "Walk somewhere.", (src, args) -> src.reply(TaskResult.ok("walked").toJson()), X, MODE)
                    .example("gt_help walk 12")
                    .example("gt_help walk 12 --mode sprint")
                    .note("Background work: the result arrives as a task_finished event.")
                    .note("Does not ask your owner.")
                    .seeAlso("gt_help note")
                    .promote("gt_help_walk", "Walk somewhere, as a tool.");
            g.client("note", "Write a note.", (src, args) -> src.reply(TaskResult.ok("noted").toJson()), BODY)
                    .example("gt_help note buy more torches");
        });
        door().registerCommands("gt_grouped", "A group whose action takes a batch of flags.", g ->
                g.server("go", "Go somewhere.", (src, args) -> src.reply(TaskResult.ok("went").toJson()),
                                X, FAST, WET, DRY, LATE)
                        .example("gt_grouped go 12 --wet true --late 3"));
        door().registerCommands("gt_many", "A group with a long list.", g -> {
            for (int i = 1; i <= 25; i++) {
                String name = String.format("act%02d", i);
                g.server(name, "Action number " + i + ". " + "Long words. ".repeat(250),
                        (src, args) -> src.reply(TaskResult.ok("done").toJson()))
                        .example("gt_many " + name);
            }
        });
    }

    private static final String GROUP_HELP = """
            gt_help: A group the tests read. Actions:
              gt_help walk <x> [--mode <word>] — Walk somewhere.
              gt_help note <body...> — Write a note.
            gt_help <action> --help explains one action.""";

    @Test
    void aGroupListsItsActionsWithUsageAndOneSentenceEach() {
        CliFixture.Outcome help = onClient("gt_help --help");
        assertTrue(help.success());
        assertEquals(GROUP_HELP, help.message(), "组的帮助一行一个动作,例子、注意、相关命令都不进来");
    }

    @Test
    void anActionGivesEverything() {
        assertEquals("""
                gt_help walk <x> [--mode <word>]
                  Walk somewhere.
                  <x> (integer 0-100) — X coordinate.
                  --mode <word> (word; optional) — How to walk. Values: walk or sprint. Omit to walk.
                  Examples:
                    gt_help walk 12
                    gt_help walk 12 --mode sprint
                  Notes:
                    Background work: the result arrives as a task_finished event.
                    Does not ask your owner.
                  See also: gt_help note
                  Shortcut tool: gt_help_walk.""", onClient("gt_help walk --help").message());
        assertEquals("""
                gt_help note <body...>
                  Write a note.
                  <body...> (text, the rest of the line) — What to write. Values: any text; the owner reads it \
                as written.
                  Examples:
                    gt_help note buy more torches""",
                onClient("gt_help note --help").message(), "没有注意与相关命令时那两块不出现");
    }

    @Test
    void aFlagGroupIsOneCellInTheUsageAndListedInFullUnderItsNameInTheActionHelp() {
        assertEquals("""
                gt_grouped: A group whose action takes a batch of flags. Actions:
                  gt_grouped go <x> [--fast <boolean>] [path flags] [time flags] — Go somewhere.
                gt_grouped <action> --help explains one action.""", onClient("gt_grouped --help").message());
        assertEquals("""
                gt_grouped go <x> [--fast <boolean>] [path flags] [time flags]
                  Go somewhere.
                  <x> (integer 0-100) — X coordinate.
                  --fast <boolean> (true or false; optional) — Run.
                  Path flags:
                    --wet <boolean> (true or false; optional) — Wade through water.
                    --dry <boolean> (true or false; optional) — Stay out of water. Omit to wade when it is shorter.
                  Time flags:
                    --late <integer> (integer 0-9; optional) — How late may she be.
                  Examples:
                    gt_grouped go 12 --wet true --late 3""", onClient("gt_grouped go --help").message());
        assertTrue(onServer("gt_grouped go 12 --dry true --fast true --late 1").success(),
                "归组只改帮助的排法,标志照样顺序随意地写");
    }

    @Test
    void onlyAnOptionalParameterJoinsAFlagGroupUnderALowercaseName() {
        assertThrows(IllegalArgumentException.class, () -> Param.required("x", ArgType.word(), "X.").group("path flags"));
        assertThrows(IllegalArgumentException.class, () -> Param.optional("x", ArgType.word(), "X.").group("Path"));
        assertThrows(IllegalArgumentException.class, () -> Param.optional("x", ArgType.word(), "X.").group(" "));
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
        CliFixture.Outcome server = onServer("gt_help --help");
        assertEquals(GROUP_HELP, server.message(), "服务端解析同一棵树,帮助一字不差");
        assertFalse(onClient("gt_help walk --help").forwarded, "帮助当场回,不跑服务端");
    }

    @Test
    void theRootListsEveryGroupInOneSentenceAndTheIndexListsThemLikeTheSkills() {
        for (String line : new String[]{"help", "--help"}) {
            String root = onClient(line).message();
            assertTrue(root.startsWith("<group> <action> [arguments]. Command groups:\n"), root);
            assertTrue(root.contains("\n  gt_help — A group the tests read.\n"), root);
            assertTrue(root.endsWith("\n<group> --help lists a group's actions. A line starting with / is a native "
                    + "command instead (/help lists those)."), root);
        }
        String index = NumenCli.index();
        assertTrue(index.startsWith("<commands>\nThe following command groups are available for use with the "
                + "command tool:\n"), index);
        assertTrue(index.contains("\n- gt_help: A group the tests read.\n"), index);
        assertTrue(index.indexOf("- gt_help:") < index.indexOf("- gt_many:"), "按名字排序: " + index);
        assertTrue(index.endsWith("\n</commands>"), index);
        assertEquals(index, NumenCli.index(), "字节稳定");
    }

    /** 帮助和动作自己列的清单同一个预算:二十五个动作、每个两三千字节的说明,一页放不下。 */
    @Test
    void aLongListIsPagedAndSaysHowToTurnThePage() {
        String first = onClient("gt_many --help").message();
        assertTrue(first.startsWith("gt_many: A group with a long list. Actions:\n"
                + "  gt_many act01 — Action number 1. Long words."), first.substring(0, 80));
        int shown = ListingTest.shownTo(first, 25, "gt_many --help --page 2");
        assertTrue(first.endsWith(" to continue.]\ngt_many <action> --help explains one action."),
                first.substring(first.length() - 120));
        assertTrue(first.contains(String.format("gt_many act%02d — ", shown)), "显示到的那一条在这一页");
        assertFalse(first.contains(String.format("gt_many act%02d — ", shown + 1)), "下一条不在");

        String second = onClient("gt_many --help --page 2").message();
        assertTrue(second.startsWith("gt_many: A group with a long list. Actions:\n"
                + String.format("  gt_many act%02d — ", shown + 1)), second.substring(0, 80));

        CliFixture.Outcome beyond = onClient("gt_many --help --page 9");
        assertFalse(beyond.success());
        assertTrue(beyond.message().startsWith("no page 9; gt_many --help has pages 1-"), beyond.message());
    }
}
