package com.dwinovo.numen.cli;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.help;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登记处的规矩:一个组名只有一个主人,别人的组下挂不上东西;名字、参数、例子、帮助正文写错或撞了,都在登记的那一刻炸。相关动作
 * 指向别的组,在登记处第一次被用时对着全部的组查;那之后登记的组在自己登记那一刻查。登记动作不加工具。
 */
class CommandRegistrationTest {

    private static final Action.OnServer OK = (src, args) -> src.reply(TaskResult.ok("ok").toJson());

    @Test
    void aGroupNameHasOneOwnerAndOthersCannotGraftOntoIt() {
        NumenApi numen = door();
        numen.registerCommands("gt_owned", "Owned by the first plugin.",
                g -> g.server("mine", "The owner's.", OK).example("gt_owned.mine()"));

        assertThrows(IllegalArgumentException.class, () -> door().registerCommands("gt_owned", "Someone else's.",
                g -> g.server("graft", "Grafted on.", OK)), "第二个插件拿同一个组名");
        String listing = help("gt_owned");
        assertTrue(listing.contains("gt_owned.mine()"), listing);
        assertFalse(listing.contains("graft"), "被拒的那次一个动作都没挂上: " + listing);
        assertFalse(onServer("gt_owned graft").success());

        assertThrows(IllegalArgumentException.class, () -> numen.registerCommands("help", "Shadow the help.",
                g -> g.server("x", "x.", OK)), "help 是根上的保留名");
    }

    @Test
    void aClosedGroupTakesNoMoreActions() {
        AtomicReference<CommandGroup> leaked = new AtomicReference<>();
        AtomicReference<Action> action = new AtomicReference<>();
        door().registerCommands("gt_closed", "Closed after its block.", g -> {
            leaked.set(g);
            action.set(g.server("only", "The only action.", OK).example("gt_closed.only()"));
        });
        assertThrows(IllegalStateException.class, () -> leaked.get().server("late", "Too late.", OK));
        assertThrows(IllegalStateException.class, () -> action.get().example("gt_closed.only()"));
        assertThrows(IllegalStateException.class, () -> action.get().returns("late"));
    }

    /** 加多少动作都不加工具:她只有跑脚本的那一个工具,动作是脚本里的函数。 */
    @Test
    void registeringActionsAddsNoTools() {
        int before = com.dwinovo.numen.agent.tool.ToolRegistry.size();
        door().registerCommands("gt_no_tools", "Actions only.", g -> {
            g.server("one", "One.", OK).example("gt_no_tools.one()");
            g.client("two", "Two.", (src, args) -> src.reply(TaskResult.ok("two").toJson()))
                    .example("gt_no_tools.two()");
        });
        assertEquals(before, com.dwinovo.numen.agent.tool.ToolRegistry.size());
    }


    @Test
    void namesAndDuplicatesAreCheckedAtTheDoor() {
        NumenApi numen = door();
        assertThrows(IllegalArgumentException.class, () -> numen.registerCommands("Bad-Name", "x.",
                g -> g.server("x", "x.", OK)));
        assertThrows(IllegalArgumentException.class, () -> numen.registerCommands("gt_empty", "No actions.", g -> { }));
        assertThrows(IllegalArgumentException.class, () -> numen.registerCommands("gt_dup_action", "x.", g -> {
            g.server("same", "First.", OK);
            g.server("same", "Second.", OK);
        }));
        assertThrows(IllegalArgumentException.class, () -> numen.registerCommands("gt_no_summary", " ",
                g -> g.server("x", "x.", OK)));
    }

    @Test
    void theRestOfTheLineMustBeTheLastArgumentAndCannotBeAFlag() {
        Param<String> text = Param.required("text", ArgType.text(), "Free text.");
        Param<String> word = Param.required("word", ArgType.word(), "A word.");
        Param<String> flag = Param.optional("flag", ArgType.word(), "A flag.").whenOmitted("leave it out");
        assertThrows(IllegalArgumentException.class, () -> Param.optional("note", ArgType.text(), "Free text."));
        NumenApi numen = door();
        assertThrows(IllegalArgumentException.class, () -> numen.registerCommands("gt_text_first", "x.",
                g -> g.server("x", "x.", OK, text, word)));
        assertThrows(IllegalArgumentException.class, () -> numen.registerCommands("gt_text_flag", "x.",
                g -> g.server("x", "x.", OK, word, text, flag)));
        assertThrows(IllegalArgumentException.class, () -> numen.registerCommands("gt_dup_param", "x.",
                g -> g.server("x", "x.", OK, word, word)));
        assertThrows(IllegalArgumentException.class, () -> Param.required("Bad", ArgType.word(), "x."));
        assertThrows(IllegalArgumentException.class, () -> Param.required("ok", ArgType.word(), " "));
    }

    /**
     * 参数表的规矩写在登记处,违反就在登记那一刻抛出,插件的动作同样受约束:一次调用只有一类对象;没有必须写的选项
     * (每个选项写明不写时会怎样);开关不当对象;可以不写的对象只能是最后一个;名字不能是脚本语言用掉的。
     */
    @Test
    void theParameterRulesAreCheckedWhenRegistered() {
        NumenApi numen = door();
        Param<Integer> count = Param.required("count", ArgType.integer(1, 9), "How many.");
        Param<String> item = Param.required("item", ArgType.word(), "Which item.");
        IllegalArgumentException twoKinds = assertThrows(IllegalArgumentException.class, () -> numen.registerCommands(
                "gt_rule_kinds", "x.", g -> g.server("take", "Take.", OK, count, item)
                        .example("gt_rule_kinds.take(1, \"a\")")));
        assertEquals("gt_rule_kinds take 的位置参数有 2 类对象(integer、word):一条命令只有一类位置参数——它操作的东西,"
                + "可以多个;其余写成标志", twoKinds.getMessage());

        Param<String> from = Param.optional("from", ArgType.word(), "Where from.");
        IllegalArgumentException mustWrite = assertThrows(IllegalArgumentException.class, () -> numen.registerCommands(
                "gt_rule_flag", "x.", g -> g.server("take", "Take.", OK, count, from).example("gt_rule_flag.take(1)")));
        assertTrue(mustWrite.getMessage().startsWith("gt_rule_flag take 的参数 from 可以不写,却没写不写时会怎样"),
                mustWrite.getMessage());

        Param<Boolean> fast = Param.required("fast", ArgType.bool(), "Run.");
        IllegalArgumentException switchFirst = assertThrows(IllegalArgumentException.class, () -> numen.registerCommands(
                "gt_rule_switch", "x.", g -> g.server("go", "Go.", OK, fast).example("gt_rule_switch.go(true)")));
        assertTrue(switchFirst.getMessage().contains("是开关,不能当位置参数"), switchFirst.getMessage());

        Param<Integer> maybe = Param.optionalPositional("maybe", ArgType.integer(), "Maybe one.").whenOmitted("use 1");
        IllegalArgumentException notLast = assertThrows(IllegalArgumentException.class, () -> numen.registerCommands(
                "gt_rule_optional", "x.", g -> g.server("go", "Go.", OK, maybe, count)
                        .example("gt_rule_optional.go(1, 2)")));
        assertTrue(notLast.getMessage().contains("是可以不写的位置参数,只能是最后一个"), notLast.getMessage());

        Param<String> in = Param.optional("in", ArgType.word(), "Where in.").whenOmitted("anywhere");
        IllegalArgumentException keyword = assertThrows(IllegalArgumentException.class, () -> numen.registerCommands(
                "gt_rule_keyword", "x.", g -> g.server("go", "Go.", OK, in).example("gt_rule_keyword.go()")));
        assertTrue(keyword.getMessage().contains("的参数 in 是脚本语言的关键字"), keyword.getMessage());

        Param<Integer> again = Param.required("again", ArgType.integer(1, 9), "How many more.");
        assertDoesNotThrow(() -> numen.registerCommands("gt_rule_ok", "x.", g -> g.server("go", "Go.", OK, count,
                again, Param.optional("from", ArgType.word(), "Where from.").whenOmitted("take any"))
                .example("gt_rule_ok.go(1, 2, {from = \"chest\"})")), "同一类的位置参数可以有几个");
        assertFalse(onServer("gt_rule_kinds --help").success(), "被拒的组没有挂上树");
    }

    @Test
    void atFirstReadEveryReferenceIsResolvedAgainstAllGroupsWhateverTheirOrder() {
        CommandGroup early = new CommandGroup("gt_early", "Registered first.");
        early.server("go", "Go.", OK).example("gt_early.go()").seeAlso("gt_late come");
        early.close();
        CommandGroup late = new CommandGroup("gt_late", "Registered after the group that points at it.");
        late.server("come", "Come.", OK).example("gt_late.come()");
        late.close();

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> NumenCli.checkSeeAlso(List.of(early), Map.of("gt_early", early)));
        assertTrue(missing.getMessage().contains("gt_early go -> gt_late come"), missing.getMessage());
        assertDoesNotThrow(() -> NumenCli.checkSeeAlso(List.of(early, late), Map.of("gt_early", early, "gt_late", late)),
                "指向后登记的组:到齐之后一起查就认");
    }

    @Test
    void onceTheTreeIsInUseAGroupsReferencesAreCheckedAsItRegisters() {
        NumenApi numen = door();
        NumenCli.index();
        numen.registerCommands("gt_see_target", "Pointed at from another group.",
                g -> g.server("go", "Go.", OK).example("gt_see_target.go()"));

        assertDoesNotThrow(() -> numen.registerCommands("gt_see_ok", "Points at real actions.", g -> {
            g.server("first", "First.", OK).example("gt_see_ok.first()")
                    .seeAlso("gt_see_ok second", "gt_see_target go");
            g.server("second", "Second.", OK).example("gt_see_ok.second()");
        }), "同组(哪怕写在后面)、别组都认");
        assertTrue(help("gt_see_ok.first").endsWith("\n  See also: gt_see_ok.second, gt_see_target.go"));

        IllegalStateException broken = assertThrows(IllegalStateException.class, () -> numen.registerCommands(
                "gt_see_broken", "Points at nothing.", g -> g.server("go", "Go.", OK).example("gt_see_broken.go()")
                        .seeAlso("gt_see_target come", "gt_nowhere go", "numen gt_see_target go")));
        assertEquals("相关动作指向不存在的函数: gt_see_broken go -> gt_see_target come; "
                + "gt_see_broken go -> gt_nowhere go; gt_see_broken go -> numen gt_see_target go",
                broken.getMessage(), "写不存在的动作、不存在的组、多写了 numen 前缀,一次列全");
        assertFalse(onServer("gt_see_broken --help").success(), "查不过的组没有挂上树");
    }

    @Test
    void everyActionNeedsAnExampleThatReadsAsThatAction() {
        NumenApi numen = door();
        Param<Integer> count = Param.required("count", ArgType.integer(1, 64), "How many.");
        Param<String> from = Param.optional("from", ArgType.word(), "Where from.").whenOmitted("take any");
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class, () -> numen.registerCommands(
                "gt_no_example", "x.", g -> g.server("go", "Go.", OK)));
        assertEquals("gt_no_example go 没写例子——模型照着例子写,每个动作至少一个", none.getMessage());
        assertFalse(onServer("gt_no_example --help").success(), "被拒的组没有挂上树");

        String[][] bads = {
                {"gt_bad_example.take()", "的例子里 gt_bad_example.take 的参数读不成"},                 // 缺了必填参数
                {"gt_bad_example.take(\"many\")", "的例子里 gt_bad_example.take 的参数读不成"},       // 值读不通
                {"gt_bad_example.take(3, {form = \"x\"})", "的例子里 gt_bad_example.take 的参数读不成"}, // 没有这个选项
                {"gt_bad_example.take(3, 4)", "的例子里 gt_bad_example.take 的参数读不成"},              // 多写了东西
                {"gt_bad_example.give(3)", "的例子没调到它自己"},                                        // 落在别的动作上
                {"gt_bad_example.take(", "的例子读不通"},                                               // 不是一段脚本
                {"gt_other.take(3)", "的例子读不通"},                                                   // 别的组
                {"take 3", "的例子读不通"}};                                                            // 一行命令的写法
        for (String[] bad : bads) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> numen.registerCommands(
                    "gt_bad_example", "x.", g -> {
                        g.server("take", "Take.", OK, count, from).example("gt_bad_example.take(3)").example(bad[0]);
                        g.server("give", "Give.", OK, count).example("gt_bad_example.give(3)");
                    }), bad[0]);
            assertTrue(e.getMessage().startsWith("gt_bad_example take " + bad[1]), bad[0] + " → " + e.getMessage());
            assertTrue(e.getMessage().contains(bad[0]), "报错里写着那个例子: " + e.getMessage());
        }
        assertDoesNotThrow(() -> numen.registerCommands("gt_bad_example", "x.", g -> {
            g.server("take", "Take.", OK, count, from).example("gt_bad_example.take(3)")
                    .example("gt_bad_example.take(3, {from = \"chest\"})");
            g.server("give", "Give.", OK, count).example("gt_bad_example.give(3)");
        }), "写对了就能登记——前面几次被拒没有占住这个组名");
    }

    @Test
    void helpContentIsCheckedWhenWritten() {
        assertThrows(IllegalArgumentException.class, () -> door().registerCommands("gt_blank_help", "x.",
                g -> g.server("x", "x.", OK).example(" ")));
        assertThrows(IllegalArgumentException.class, () -> door().registerCommands("gt_blank_note", "x.",
                g -> g.server("x", "x.", OK).example("gt_blank_note.x()").note("")));
        AtomicReference<Action> leaked = new AtomicReference<>();
        door().registerCommands("gt_help_closed", "Closed after its block.",
                g -> leaked.set(g.server("x", "x.", OK).example("gt_help_closed.x()")));
        assertThrows(IllegalStateException.class, () -> leaked.get().note("Too late."), "封口之后不能再补帮助");
    }
}
