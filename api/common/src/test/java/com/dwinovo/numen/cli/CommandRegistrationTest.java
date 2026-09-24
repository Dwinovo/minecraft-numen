package com.dwinovo.numen.cli;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onClient;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登记处的规矩:一个组名只有一个主人,别人的组下挂不上东西;名字、参数、快捷工具名写错或撞了,都在登记的那一刻炸。
 */
class CommandRegistrationTest {

    private static final Action.OnServer OK = (src, args) -> src.reply(TaskResult.ok("ok").toJson());

    @Test
    void aGroupNameHasOneOwnerAndOthersCannotGraftOntoIt() {
        NumenApi numen = door();
        numen.registerCommands("gt_owned", "Owned by the first plugin.", g -> g.server("mine", "The owner's.", OK));

        assertThrows(IllegalArgumentException.class, () -> door().registerCommands("gt_owned", "Someone else's.",
                g -> g.server("graft", "Grafted on.", OK)), "第二个插件拿同一个组名");
        String listing = onClient("numen gt_owned --help").message();
        assertTrue(listing.contains("numen gt_owned mine"), listing);
        assertFalse(listing.contains("graft"), "被拒的那次一个动作都没挂上: " + listing);
        assertFalse(onClient("numen gt_owned graft").success());

        assertThrows(IllegalArgumentException.class, () -> numen.registerCommands("help", "Shadow the help.",
                g -> g.server("x", "x.", OK)), "help 是根上的保留名");
    }

    @Test
    void aClosedGroupTakesNoMoreActions() {
        AtomicReference<CommandGroup> leaked = new AtomicReference<>();
        AtomicReference<Action> action = new AtomicReference<>();
        door().registerCommands("gt_closed", "Closed after its block.", g -> {
            leaked.set(g);
            action.set(g.server("only", "The only action.", OK));
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
    void aShortcutNameThatIsTakenBlowsUpAtRegistration() {
        NumenApi numen = door();
        numen.registerCommands("gt_tool_a", "First.", g -> g.server("go", "Go.", OK).promote("gt_shared_tool", "Go."));
        assertThrows(IllegalStateException.class, () -> numen.registerCommands("gt_tool_b", "Second.",
                g -> g.server("go", "Go.", OK).promote("gt_shared_tool", "Go too.")));
        assertThrows(IllegalStateException.class, () -> numen.registerCommands("gt_tool_c", "Third.", g -> {
            Action a = g.server("go", "Go.", OK).promote("gt_tool_c_go", "Go.");
            a.promote("gt_tool_c_again", "Again.");
        }), "一个动作只提升一次");
    }
}
