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
                    .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                    .example("gt_help.walk(12)")
                    .example("gt_help.walk(12, {mode = \"sprint\"})")
                    .note("Background work: the result arrives as a task_finished event.")
                    .note("Does not ask your owner.")
                    .seeAlso("gt_help note");
            g.client("note", "Write a note.", (src, args) -> src.reply(TaskResult.ok("noted").toJson()), BODY)
                    .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                    .example("gt_help.note(\"buy more torches\")");
        });
        door().registerCommands("gt_grouped", "A group whose action takes a batch of flags.", g ->
                g.server("go", "Go somewhere.", (src, args) -> src.reply(TaskResult.ok("went").toJson()),
                                X, FAST, WET, DRY, LATE)
                        .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                        .example("gt_grouped.go(12, {wet = true, late = 3})"));
        door().registerCommands("gt_many", "A group with a long list.", g -> {
            for (int i = 1; i <= 25; i++) {
                String name = String.format("act%02d", i);
                g.server(name, "Action number " + i + ". " + "Long words. ".repeat(250),
                        (src, args) -> src.reply(TaskResult.ok("done").toJson()))
                        .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                        .example("gt_many." + name + "()");
            }
        });
    }

    private static final String GROUP_HELP = """
            ---A group the tests read.
            ---@class gt_help
            ---@field walk fun(x: integer, opts?: {mode?: string}) Walk somewhere.
            ---@field note fun(body: string) Write a note.
            gt_help = {}""";

    @Test
    void aGroupListsItsFunctionsAsOneTypedLineEach() {
        assertEquals(GROUP_HELP, help("gt_help"), "组的帮助一行一个函数的类型签名,例子、注意、相关都不进来");
    }

    @Test
    void anActionGivesEverything() {
        assertEquals("""
                ---Walk somewhere.
                ---@param x integer X coordinate.
                ---@param opts? gt_help.walk.opts
                function gt_help.walk(x, opts) end

                ---@class gt_help.walk.opts
                ---@field mode? string How to walk. Values: walk or sprint. Omit to walk.
                -- Examples:
                --   gt_help.walk(12)
                --   gt_help.walk(12, {mode = "sprint"})
                -- Notes:
                --   Background work: the result arrives as a task_finished event.
                --   Does not ask your owner.
                -- See also: gt_help.note""", help("gt_help.walk"));
        assertEquals("""
                ---Write a note.
                ---@param body string What to write. Values: any text; the owner reads it as written.
                function gt_help.note(body) end
                -- Examples:
                --   gt_help.note("buy more torches")""",
                help("gt_help.note"), "没有注意与相关时那两块不出现");
    }

    /** 标志组在写错时附的用法行里是一格;签名里选项表照样一个个列出。 */
    @Test
    void aFlagGroupIsOneCellInTheUsageAndEveryOptionIsInTheSignature() {
        assertEquals("""
                ---A group whose action takes a batch of flags.
                ---@class gt_grouped
                ---@field go fun(x: integer, opts?: {fast?: boolean, wet?: boolean, dry?: boolean, late?: integer}) Go somewhere.
                gt_grouped = {}""", help("gt_grouped"));
        assertEquals("""
                ---Go somewhere.
                ---@param x integer X coordinate.
                ---@param opts? gt_grouped.go.opts
                function gt_grouped.go(x, opts) end

                ---@class gt_grouped.go.opts
                ---@field fast? boolean Run. Omit to walk.
                ---@field wet? boolean Wade through water. Omit to keep dry.
                ---@field dry? boolean Stay out of water. Omit to wade when it is shorter.
                ---@field late? integer How late may she be. Omit to be on time.
                -- Examples:
                --   gt_grouped.go(12, {wet = true, late = 3})""", help("gt_grouped.go"));
        assertTrue(onServer("gt_grouped go 12 --wet --late 1 --nope").message()
                        .contains("\nusage: gt_grouped.go(x, {fast=true, …path flags, …time flags})"),
                "写错时附的用法行里一组标志是一格");
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
        String index = NumenCli.index(com.dwinovo.numen.script.Modules.builtin());
        assertTrue(index.startsWith("<api>\nCall these from the lua tool. `api.help(\"move\")` lists a group's or a module's "
                + "functions with their types; `api.help(\"move.go\")` explains one in full (every argument, what it "
                + "returns, examples).\nValues the groups share:\n---A position."), index);
        assertTrue(index.contains("\n---@class Pos\n---@field x number\n"), "共用的类在索引里声明: " + index);
        assertTrue(index.contains("\ngt_help — A group the tests read. walk, note\n"),
                "一组一行:说明与它的函数名: " + index);
        assertTrue(index.indexOf("gt_help — ") < index.indexOf("gt_many — "), "按名字排序: " + index);
        assertTrue(index.endsWith("\n</api>"), index);
        assertTrue(index.contains("You neither need nor can require them; there is no require."),
                "模块按名字直接用、不需要也不能 require,这一句在索引里: " + index);
        assertEquals(index, NumenCli.index(com.dwinovo.numen.script.Modules.builtin()), "字节稳定");
        for (String line : new String[]{"help", "--help"}) {
            String root = onServer(line).message();
            assertTrue(root.startsWith("Call these from the lua tool."), root.substring(0, 60));
            assertTrue(root.contains("\ngt_help — A group the tests read. walk, note\n"), "根上的帮助就是索引");
        }
    }

    /**
     * 一行命令要的帮助和动作自己列的清单同一个预算:二十五个函数、每个两三千字节的说明,一页放不下,翻页。脚本里 api.help 返回的是
     * 全文(数据不分页,要看就 print,筛过再打)。
     */
    @Test
    void aLongGroupIsPagedOnTheLineAndWholeInAScript() {
        String first = onServer("gt_many --help").message();
        assertTrue(first.startsWith("---A group with a long list.\n---@class gt_many\n"
                + "---@field act01 fun() Action number 1. Long words."), first.substring(0, 80));
        // 一页的条目:类那一行、二十五个函数各一行、末尾那张表;显示到第 N 条就是显示到第 N - 1 个函数
        int shown = ListingTest.shownTo(first, 27, 2);
        assertTrue(first.contains(String.format("---@field act%02d fun() ", shown - 1)), "显示到的那一条在这一页");
        assertFalse(first.contains(String.format("---@field act%02d fun() ", shown)), "下一条不在");
        String second = onServer("gt_many --help --page 2").message();
        assertTrue(second.startsWith("---A group with a long list.\n"
                + String.format("---@field act%02d fun() ", shown)), second.substring(0, 80));

        String whole = said("api.help(\"gt_many\")");
        assertTrue(whole.contains("---@field act25 fun() Action number 25."), "脚本里拿到全文");
    }

    private static String said(String call) {
        CliFixture.Outcome run = CliFixture.lua("return " + call);
        assertTrue(run.success(), run.message());
        return run.json().getAsJsonObject("data").get("returned").getAsString();
    }
}
