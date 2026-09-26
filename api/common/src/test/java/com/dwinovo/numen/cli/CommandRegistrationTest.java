package com.dwinovo.numen.cli;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onClient;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登记处的规矩:一个组名只有一个主人,别人的组下挂不上东西;名字、参数、快捷工具名、帮助正文写错或撞了,都在登记的
 * 那一刻炸。相关命令指向别的组,在命令树第一次被读时对着全部的组查;那之后登记的组在自己登记那一刻查。
 */
class CommandRegistrationTest {

    private static final Action.OnServer OK = (src, args) -> src.reply(TaskResult.ok("ok").toJson());

    @Test
    void aGroupNameHasOneOwnerAndOthersCannotGraftOntoIt() {
        NumenApi numen = door();
        numen.registerCommands("gt_owned", "Owned by the first plugin.",
                g -> g.server("mine", "The owner's.", OK).example("gt_owned mine"));

        assertThrows(IllegalArgumentException.class, () -> door().registerCommands("gt_owned", "Someone else's.",
                g -> g.server("graft", "Grafted on.", OK)), "第二个插件拿同一个组名");
        String listing = onClient("gt_owned --help").message();
        assertTrue(listing.contains("gt_owned mine"), listing);
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
            action.set(g.server("only", "The only action.", OK).example("gt_closed only"));
        });
        assertThrows(IllegalStateException.class, () -> leaked.get().server("late", "Too late.", OK));
        assertThrows(IllegalStateException.class, () -> action.get().promote("gt_closed_late", "Too late."));
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
        Param<String> flag = Param.optional("flag", ArgType.word(), "A flag.");
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

    @Test
    void atFirstReadEveryReferenceIsResolvedAgainstAllGroupsWhateverTheirOrder() {
        CommandGroup early = new CommandGroup("gt_early", "Registered first.");
        early.server("go", "Go.", OK).example("gt_early go").seeAlso("gt_late come");
        early.close();
        CommandGroup late = new CommandGroup("gt_late", "Registered after the group that points at it.");
        late.server("come", "Come.", OK).example("gt_late come");
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
                g -> g.server("go", "Go.", OK).example("gt_see_target go"));

        assertDoesNotThrow(() -> numen.registerCommands("gt_see_ok", "Points at real actions.", g -> {
            g.server("first", "First.", OK).example("gt_see_ok first")
                    .seeAlso("gt_see_ok second", "gt_see_target go");
            g.server("second", "Second.", OK).example("gt_see_ok second");
        }), "同组(哪怕写在后面)、别组都认");
        assertTrue(onClient("gt_see_ok first --help").message()
                .endsWith("\n  See also: gt_see_ok second, gt_see_target go"));

        IllegalStateException broken = assertThrows(IllegalStateException.class, () -> numen.registerCommands(
                "gt_see_broken", "Points at nothing.", g -> g.server("go", "Go.", OK).example("gt_see_broken go")
                        .seeAlso("gt_see_target come", "gt_nowhere go", "numen gt_see_target go")));
        assertEquals("相关命令指向不存在的动作: gt_see_broken go -> gt_see_target come; "
                + "gt_see_broken go -> gt_nowhere go; gt_see_broken go -> numen gt_see_target go",
                broken.getMessage(), "写不存在的动作、不存在的组、多写了 numen 前缀,一次列全");
        assertFalse(onServer("gt_see_broken --help").success(), "查不过的组没有挂上树");
    }

    @Test
    void everyActionNeedsAnExampleThatReadsAsThatAction() {
        NumenApi numen = door();
        Param<Integer> count = Param.required("count", ArgType.integer(1, 64), "How many.");
        Param<String> from = Param.optional("from", ArgType.word(), "Where from.");
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class, () -> numen.registerCommands(
                "gt_no_example", "x.", g -> g.server("go", "Go.", OK)));
        assertEquals("gt_no_example go 没写例子——模型照着例子写,每个动作至少一个", none.getMessage());
        assertFalse(onServer("gt_no_example --help").success(), "被拒的组没有挂上树");

        for (String bad : new String[]{
                "gt_bad_example take",              // 缺了必填参数
                "gt_bad_example take many",         // 值读不通
                "gt_bad_example take 3 --form x",   // 没有这个标志
                "gt_bad_example take 3 extra",      // 多写了东西
                "gt_bad_example give 3",            // 落在别的动作上
                "gt_bad_example take --help",       // 落在帮助上
                "gt_other take 3",                  // 别的组
                "numen gt_bad_example take 3"}) {   // 多写了 numen 前缀
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> numen.registerCommands(
                    "gt_bad_example", "x.", g -> {
                        g.server("take", "Take.", OK, count, from).example("gt_bad_example take 3").example(bad);
                        g.server("give", "Give.", OK, count).example("gt_bad_example give 3");
                    }), bad);
            assertEquals("gt_bad_example take 的例子写不通,或者落在别的动作上: " + bad, e.getMessage());
        }
        assertDoesNotThrow(() -> numen.registerCommands("gt_bad_example", "x.", g -> {
            g.server("take", "Take.", OK, count, from).example("gt_bad_example take 3")
                    .example("gt_bad_example take 3 --from chest");
            g.server("give", "Give.", OK, count).example("gt_bad_example give 3");
        }), "写对了就能登记——前面几次被拒没有占住这个组名");
    }

    @Test
    void helpContentIsCheckedWhenWritten() {
        assertThrows(IllegalArgumentException.class, () -> door().registerCommands("gt_blank_help", "x.",
                g -> g.server("x", "x.", OK).example(" ")));
        assertThrows(IllegalArgumentException.class, () -> door().registerCommands("gt_blank_note", "x.",
                g -> g.server("x", "x.", OK).example("gt_blank_note x").note("")));
        AtomicReference<Action> leaked = new AtomicReference<>();
        door().registerCommands("gt_help_closed", "Closed after its block.",
                g -> leaked.set(g.server("x", "x.", OK).example("gt_help_closed x")));
        assertThrows(IllegalStateException.class, () -> leaked.get().note("Too late."), "封口之后不能再补帮助");
    }

    @Test
    void aShortcutNameThatIsTakenBlowsUpAtRegistration() {
        NumenApi numen = door();
        numen.registerCommands("gt_tool_a", "First.",
                g -> g.server("go", "Go.", OK).example("gt_tool_a go").promote("gt_shared_tool", "Go."));
        assertThrows(IllegalStateException.class, () -> numen.registerCommands("gt_tool_b", "Second.",
                g -> g.server("go", "Go.", OK).example("gt_tool_b go").promote("gt_shared_tool", "Go too.")));
        assertThrows(IllegalStateException.class, () -> numen.registerCommands("gt_tool_c", "Third.", g -> {
            Action a = g.server("go", "Go.", OK).promote("gt_tool_c_go", "Go.");
            a.promote("gt_tool_c_again", "Again.");
        }), "一个动作只提升一次");
    }
}
