package com.dwinovo.numen.core.tools.work;

import java.util.List;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.task.fish.FishTaskRecord;
import com.dwinovo.numen.cli.Target;
import com.dwinovo.numen.core.task.dig.DigCompanionTask;
import com.dwinovo.numen.core.tools.BlockActionOps;
import com.dwinovo.numen.core.tools.InventoryOps;
import com.dwinovo.numen.task.TaskDispatch;

import net.minecraft.resources.ResourceLocation;

/**
 * {@code work}:采集类的活——挖方块、钓鱼。两个动作都站在原地干,占身体,交任务槽:受理即回执,收尾走 task_finished;开始不了的
 * (手够得着的一格都没有、站的地方抛不进水……)受理之前就当场拒绝,判据在各自的任务里。
 *
 * <p>{@code dig} 只挖她站在原地手够得着的格,挡在前面的一并挖开,不走动、不捡({@link DigCompanionTask});{@code fish} 只钓,
 * 不走去岸边、不追战果。走到够得着的地方是 {@code numen.move.goto_(…, {arrive = "dig"})} 的事;捡是库里的 {@code numen.work.collect}:
 * 原版玩家走近掉落物就捡起来,所以捡就是扫掉落物、走到它跟前。组合交给脚本。
 */
public final class WorkCommands {

    static final String GROUP = "work";

    private static final Param<List<Target>> DIG_TARGETS = Param.required("blocks", ArgType.list(ArgType.target()),
            "What to dig, as many as you like: a Block from a query is dug only while that cell still holds that "
                    + "block; a Pos is dug whatever it holds, air and fluid skipped.")
            .values("Blocks (a scan's cluster.blocks, numen.scan.block(...)) or Pos");
    private static final Param<Integer> DIG_COUNT = Param.optional("count",
            ArgType.integer(1, BlockActionOps.MAX_DIG_COUNT), "How many cells to dig at most.")
            .whenOmitted("dig every cell of it within reach");

    private WorkCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Gathering where you stand: digging blocks within reach, fishing. numen.work.collect "
                + "(library) walks to the drops and picks them up.", WorkCommands::actions);
    }

    private static void actions(CommandGroup work) {
        work.server("dig", "Dig the given blocks that are within reach of where you stand.", WorkCommands::dig,
                        DIG_TARGETS, DIG_COUNT)
                .returns(ScriptType.table(
                        ScriptType.field("dug", ScriptType.INTEGER, "Cells it dug."),
                        ScriptType.field("left", ScriptType.INTEGER, "Cells of what you gave still to dig."),
                        ScriptType.field("out_of_reach", ScriptType.INTEGER, "Of those, how many your hand does not "
                                + "reach from where you stand."),
                        ScriptType.optional("nearest", Shapes.POS.type(), "The nearest of those out of reach.")))
                .example("numen.work.dig({{name = \"iron_ore\", pos = {x = 120, y = 12, z = -35}}, "
                        + "{name = \"iron_ore\", pos = {x = 121, y = 12, z = -35}}})")
                .example("numen.work.dig({x = 120, y = 12, z = -35})")
                .example("local r = numen.work.dig({{x = 120, y = 64, z = -35}, {x = 120, y = 65, z = -35}}, {count = 1})\n"
                        + "print(r.dug, r.left, r.out_of_reach)")
                .note("A Block from `numen.scan.block` or a cluster's blocks from `numen.scan.blocks` go in as they are.")
                .note("Digs only what your hand reaches from where you stand: it never walks and never picks up. Get "
                        + "within reach first with `numen.move.goto_` and arrive \"dig\", given the same blocks (it picks the "
                        + "spot that reaches the most cells), dig, and pick the drops up with `numen.work.collect()`.")
                .note("Background work: before it starts it checks something within reach can be dug, harvested "
                        + "with your tools and is allowed; when nothing is, it fails with kind out_of_reach (or denied, "
                        + "failed) and a hint with the numen.move.goto_ call to copy — no task starts and whatever you were "
                        + "doing goes on. It returns when the job ends: how many cells it dug and how many are still out "
                        + "of reach, the nearest of them as a pos.")
                .note("What to dig comes from what you give: a Block is dug only while its cell still holds that block "
                        + "(mining what a scan found); a Pos is dug whatever it holds (a pit, clearing), air and fluid "
                        + "skipped. left says how many of them are still to dig.")
                .note("A block in the way of your hand is dug open too when it is natural terrain. A block in the way "
                        + "that needs your owner's consent or that their rules forbid is not touched: the reply names "
                        + "it, where it is and why.")
                .note("Takes the best tool for each block; only digs what your tools actually harvest, and says so "
                        + "when nothing qualifies. Asks your owner before breaking a named block their rules want "
                        + "asked about; a refusal stops the job with the reason.")
                .seeAlso("move goto_", "work collect", "scan blocks", "task stop");
        work.server("fish", "Cast a fishing rod once from where you stand, wait for a bite and reel it in.",
                        WorkCommands::fish)
                .returns("caught", ScriptType.listOf(ScriptType.STRING))
                .example("numen.work.fish()")
                .example("for i = 1, 5 do numen.work.fish() end")
                .note("Background work: refused with the reason when she carries no fishing rod, does not stand on "
                        + "dry ground, or has no open water to cast into from where she stands — no task id, no "
                        + "task_finished. Otherwise it returns what came up on the line, minecraft:cod x1.")
                .note("One cast per call: a cast that misses the water, hooks an entity or gets no bite in a minute "
                        + "fails and says why; cast again by calling it again.")
                .note("It never walks: stand on the shore first. The reel throws each catch to her; one that lands "
                        + "short lies on the ground for `numen.work.collect()`.")
                .note("A catch is one bite reeled in: fish, junk or treasure, with vanilla loot, rod wear and "
                        + "stats.")
                .seeAlso("work collect", "task stop");
    }

    /** 点名的几格读成格子的那一步在 {@link BlockActionOps#dig},够不够得着在任务受理之前的准备里判。 */
    private static void dig(ServerSource src, CommandArgs args) {
        TaskDispatch.setTask(src, new BlockActionOps().dig(src, args.get(DIG_TARGETS), args.get(DIG_COUNT)));
    }

    /** 抛一竿:一次调用一竿,钓几条是程序里调几次。 */
    private static void fish(ServerSource src, CommandArgs args) {
        TaskDispatch.setTask(src, new FishTaskRecord(src));
    }
}
