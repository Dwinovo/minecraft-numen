package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.help;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帮助是模型读的界面,写成脚本里的样子:三层的样子逐字钉住,措辞一变这里就红。只有函数这一层给全(参数的取值提示、例子、注意、
 * 相关),组的帮助一行一个函数;列表超过一页时说还剩多少、怎么翻;系统提示的索引与根帮助是同一份,两个前端要到的帮助一字不差。
 */
class CommandHelpTest {

    static final Param<Integer> X = Param.required("x", ArgType.integer(0, 100), "X coordinate.");
    static final Param<String> MODE = Param.optional("mode", ArgType.word(), "How to walk.")
            .values("walk or sprint")
            .whenOmitted("walk");
    static final Param<String> BODY = Param.required("body", ArgType.text(), "What to write.")
            .values("any text; the owner reads it as written");
    static final Param<Boolean> FAST = Param.optional("fast", ArgType.bool(), "Run.").whenOmitted("walk");
    static final Param<Boolean> WET = Param.optional("wet", ArgType.bool(), "Wade through water.")
            .whenOmitted("keep dry")
            .group("path flags");
    static final Param<Boolean> DRY = Param.optional("dry", ArgType.bool(), "Stay out of water.")
            .whenOmitted("wade when it is shorter")
            .group("path flags");
    static final Param<Integer> LATE = Param.optional("late", ArgType.integer(0, 9), "How late may she be.")
            .whenOmitted("be on time")
            .group("time flags");

    @BeforeAll
    static void register() {
        door().registerCommands("gt_help", "A group the tests read.", g -> {
            g.server("walk", "Walk somewhere.", (src, args) -> src.reply(TaskResult.ok("walked").toJson()), X, MODE)
                    .example("gt_help.walk(12)")
                    .example("gt_help.walk(12, {mode = \"sprint\"})")
                    .note("Background work: the result arrives as a task_finished event.")
                    .note("Does not ask your owner.")
                    .seeAlso("gt_help note");
            g.client("note", "Write a note.", (src, args) -> src.reply(TaskResult.ok("noted").toJson()), BODY)
                    .example("gt_help.note(\"buy more torches\")");
        });
        door().registerCommands("gt_grouped", "A group whose action takes a batch of flags.", g ->
                g.server("go", "Go somewhere.", (src, args) -> src.reply(TaskResult.ok("went").toJson()),
                                X, FAST, WET, DRY, LATE)
                        .example("gt_grouped.go(12, {wet = true, late = 3})"));
        door().registerCommands("gt_many", "A group with a long list.", g -> {
            for (int i = 1; i <= 25; i++) {
                String name = String.format("act%02d", i);
                g.server(name, "Action number " + i + ". " + "Long words. ".repeat(250),
                        (src, args) -> src.reply(TaskResult.ok("done").toJson()))
                        .example("gt_many." + name + "()");
            }
        });
    }

    private static final String GROUP_HELP = """
            gt_help — A group the tests read.
              gt_help.walk(x, {mode=…}) — Walk somewhere.
              gt_help.note(body...) — Write a note.""";

    @Test
    void aGroupListsItsFunctionsWithUsageAndOneSentenceEach() {
        assertEquals(GROUP_HELP, help("gt_help"), "组的帮助一行一个函数,例子、注意、相关都不进来");
    }

    @Test
    void anActionGivesEverything() {
        assertEquals("""
                gt_help.walk(x, {mode=…})
                  Walk somewhere.
                  x (integer 0-100) — X coordinate.
                  mode= (word; optional) — How to walk. Values: walk or sprint. Omit to walk.
                  Examples:
                    gt_help.walk(12)
                    gt_help.walk(12, {mode = "sprint"})
                  Notes:
                    Background work: the result arrives as a task_finished event.
                    Does not ask your owner.
                  See also: gt_help.note""", help("gt_help.walk"));
        assertEquals("""
                gt_help.note(body...)
                  Write a note.
                  body... (text) — What to write. Values: any text; the owner reads it as written.
                  Examples:
                    gt_help.note("buy more torches")""",
                help("gt_help.note"), "没有注意与相关时那两块不出现");
    }

    @Test
    void aFlagGroupIsOneCellInTheUsageAndListedInFullUnderItsNameInTheActionHelp() {
        assertEquals("""
                gt_grouped — A group whose action takes a batch of flags.
                  gt_grouped.go(x, {fast=true, …path flags, …time flags}) — Go somewhere.""", help("gt_grouped"));
        assertEquals("""
                gt_grouped.go(x, {fast=true, …path flags, …time flags})
                  Go somewhere.
                  x (integer 0-100) — X coordinate.
                  fast=true|false (switch; optional) — Run. Omit to walk.
                  Path flags (options):
                    wet=true|false (switch; optional) — Wade through water. Omit to keep dry.
                    dry=true|false (switch; optional) — Stay out of water. Omit to wade when it is shorter.
                  Time flags (options):
                    late= (integer 0-9; optional) — How late may she be. Omit to be on time.
                  Examples:
                    gt_grouped.go(12, {wet = true, late = 3})""", help("gt_grouped.go"));
        assertTrue(onServer("gt_grouped go 12 --dry --fast --late 1").success(),
                "归组只改帮助的排法,标志照样顺序随意地写");
    }

    @Test
    void onlyAnOptionalParameterJoinsAFlagGroupUnderALowercaseName() {
        assertThrows(IllegalArgumentException.class, () -> Param.required("x", ArgType.word(), "X.").group("path flags"));
        assertThrows(IllegalArgumentException.class, () -> Param.optional("x", ArgType.word(), "X.").group("Path"));
        assertThrows(IllegalArgumentException.class, () -> Param.optional("x", ArgType.word(), "X.").group(" "));
    }

    @Test
    void theValueHintsAreCheckedWhenDeclared() {
        assertThrows(IllegalArgumentException.class, () -> Param.required("x", ArgType.word(), "X.").whenOmitted("y"),
                "必填参数没有\"不写时\"");
        assertThrows(IllegalArgumentException.class, () -> Param.optional("x", ArgType.word(), "X.").whenOmitted(" "));
        assertThrows(IllegalArgumentException.class, () -> Param.optional("x", ArgType.word(), "X.").values(""));
    }

    /** 帮助只有一份:一行命令的 {@code --help} 与脚本里的 {@code api.help} 一字不差。 */
    @Test
    void theHelpIsTheSameWhicheverFrontAsksForIt() {
        assertEquals(GROUP_HELP, onServer("gt_help --help").message(), "一行命令要的组帮助");
        assertEquals(help("gt_help.walk"), onServer("gt_help walk --help").message(), "一行命令要的函数帮助");
    }

    @Test
    void theIndexListsEveryFunctionByGroupAndTheRootIsTheIndex() {
        String index = NumenCli.index();
        assertTrue(index.startsWith("<api>\nCall these from the lua tool. Each line: how to call it — what it does. "
                + "`api.help(\"work.dig\")` explains one function in full, `api.help(\"work\")` one group.\n"), index);
        assertTrue(index.contains("\n" + GROUP_HELP + "\n"), "一组一段,和组的帮助同一份: " + index);
        assertTrue(index.indexOf("gt_help — ") < index.indexOf("gt_many — "), "按名字排序: " + index);
        assertTrue(index.endsWith("\n</api>"), index);
        assertEquals(index, NumenCli.index(), "字节稳定");
        for (String line : new String[]{"help", "--help"}) {
            String root = onServer(line).message();
            assertTrue(root.startsWith("Call these from the lua tool."), root.substring(0, 60));
            assertTrue(root.contains("\n" + GROUP_HELP + "\n"), "根上的帮助就是索引");
        }
    }

    /** 帮助和动作自己列的清单同一个预算:二十五个函数、每个两三千字节的说明,一页放不下。 */
    @Test
    void aLongGroupIsPagedAndSaysHowToTurnThePage() {
        String first = said("api.help(\"gt_many\")");
        assertTrue(first.startsWith("gt_many — A group with a long list.\n"
                + "  gt_many.act01() — Action number 1. Long words."), first.substring(0, 80));
        int shown = ListingTest.shownTo(first, 25, 2);
        assertTrue(first.contains(String.format("gt_many.act%02d() — ", shown)), "显示到的那一条在这一页");
        assertFalse(first.contains(String.format("gt_many.act%02d() — ", shown + 1)), "下一条不在");

        String second = said("api.help(\"gt_many\", {page = 2})");
        assertTrue(second.startsWith("gt_many — A group with a long list.\n"
                + String.format("  gt_many.act%02d() — ", shown + 1)), second.substring(0, 80));

        CliFixture.Outcome beyond = CliFixture.lua("api.help(\"gt_many\", {page = 9})");
        assertFalse(beyond.success());
        assertTrue(beyond.message().contains("api.help: no page 9; this list has pages 1-"), beyond.message());
        assertEquals(first, onServer("gt_many --help").message(), "一行命令要的同一页");
    }

    private static String said(String call) {
        CliFixture.Outcome run = CliFixture.lua("return " + call);
        assertTrue(run.success(), run.message());
        return run.json().getAsJsonObject("data").get("returned").getAsString();
    }
}
