package com.dwinovo.numen.plugins.minecraft.tools;

import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.plugins.minecraft.WorkProfile;
import com.dwinovo.numen.plugins.minecraft.build.BuildStates;
import com.dwinovo.numen.plugins.minecraft.build.Canvas;
import com.dwinovo.numen.plugins.minecraft.build.Changes;
import com.dwinovo.numen.plugins.minecraft.build.Design;
import com.dwinovo.numen.plugins.minecraft.task.build.BuildCompanionTask;
import com.dwinovo.numen.plugins.minecraft.task.build.BuildOrder;
import com.dwinovo.numen.plugins.minecraft.task.build.BuildSurvey;
import com.dwinovo.numen.plugins.minecraft.task.build.BuildTaskRecord;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Job;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.api.sdk.ServerCall;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 建造动世界的这一半:把一串格变成要盖的样子({@code minecraft.build.place})、数还差什么和要花什么({@code minecraft.build.diff})。
 *
 * <p>两条路是同一条:摆出这串格({@link #plan})→ 和世界比出要动的格({@link Changes})→ 没有要动的就不派活,有就交执行器,
 * 她站在原地放,每格轮到一次就收场。{@code minecraft.build.diff} 摆同一串格、比同一份差异,按执行器挑格的同一个判据
 * ({@link BuildSurvey})数,不派活。
 */
public final class BuildOps {

    private BuildOps() {}

    /** 把这一处变成要盖的样子:整份放一遍;已经是这个样子就不派活,当场交回什么都没放。 */
    public static Job<BuildCompanionTask.Placed> place(ServerCall call, Design design) {
        NumenPlayer her = call.her();
        List<BuildTaskRecord.Target> targets = plan(her, design);
        if (Changes.between(targets, seen(her.serverLevel())).none()) {
            return Job.done(BuildCompanionTask.Placed.NOTHING);
        }
        boolean consume = !WorkProfile.of(her).freeMaterials();
        long deadline = her.level().getGameTime() + BuildOrder.deadlineTicks(targets.size());
        return Job.of(new BuildTaskRecord(call, deadline, targets, consume));
    }

    /** 离要盖的样子还差什么:{@code minecraft.build.diff} 交回的值。 */
    @Doc("What a spot still lacks to look like a design.")
    public record Diff(@Doc("Cells still to do.") int left,
                       @Doc("Of those, the cells minecraft.build.place puts in now.") int ready,
                       @Doc("Up to " + LISTED_DIG + " cells holding another block to dig out first, nearest first.")
                       List<BlockPos> dig,
                       @Doc("Cells holding another block with nothing of yours to put there.") int noStock,
                       @Doc("Cells that differ and that you leave alone.") int skipped,
                       @Doc("Cells involving liquid, which minecraft.build.place does not place or drain: water or lava in the "
                               + "design, liquid where the design wants air, and blocks whose water is left out "
                               + "(waterlogged). Not counted in left or in materials.") int liquid,
                       @Doc("Items for the cells still to do, all of them, most first.") Map<String, Integer> materials,
                       @Doc("Of those, what you are still short of, in survival; empty means you carry enough. "
                               + "Absent when building costs nothing.") Optional<Map<String, Integer>> shortOf) {}

    /**
     * 这一处离要盖的样子还差什么:还剩几格、几格此刻放得进去、几格要先挖开(给出最近的几格),以及剩下的格要花的
     * 全部料与她还缺多少。只读,当场回;数的判据与 {@link #place} 挑格的是同一个,料的件数与逐格实扣是同一个函数({@link BuildBill})。
     */
    public static Diff diff(NumenPlayer her, Design design) {
        List<BuildTaskRecord.Target> targets = plan(her, design);
        if (Changes.between(targets, seen(her.serverLevel())).none()) {
            return new Diff(0, 0, List.of(), 0, 0, 0, Map.of(), shortOf(her, Map.of()));
        }
        BuildTaskRecord record = new BuildTaskRecord("minecraft.build.diff", "", 0, targets,
                !WorkProfile.of(her).freeMaterials());
        BuildSurvey.Tally tally = BuildSurvey.of(her, record).tally();
        Map<Item, Integer> cost = BuildBill.cost(tally.pending());
        return new Diff(tally.left(), tally.count(BuildSurvey.State.READY),
                List.copyOf(tally.dig().subList(0, Math.min(LISTED_DIG, tally.dig().size()))),
                tally.count(BuildSurvey.State.SHORT),
                tally.count(BuildSurvey.State.SKIPPED), tally.count(BuildSurvey.State.LIQUID) + tally.heldWater(),
                BuildBill.items(cost), shortOf(her, cost));
    }

    /** {@code minecraft.build.diff} 列出几格要先挖开的:一次 {@code minecraft.work.dig} 交得完的量。 */
    private static final int LISTED_DIG = 16;

    /** 她还缺多少:生存模式下按 {@code cost} 数,免耗材时没有这一项。 */
    private static Optional<Map<String, Integer>> shortOf(NumenPlayer her, Map<Item, Integer> cost) {
        return shortOf(WorkProfile.of(her).freeMaterials(), her.getInventory(), cost);
    }

    /** 同上,对着免不免耗材与她的背包数。 */
    static Optional<Map<String, Integer>> shortOf(boolean freeMaterials, Inventory carried, Map<Item, Integer> cost) {
        return freeMaterials ? Optional.empty() : Optional.of(BuildBill.items(BuildBill.shortOf(carried, cost)));
    }

    /** 摆出这串格:每一格读成方块,后写覆盖先写。 */
    private static List<BuildTaskRecord.Target> plan(NumenPlayer her, Design design) {
        Canvas canvas = new Canvas();
        for (Design.Cell cell : design.cells()) {
            if (cell.block() == null) {
                throw new ApiError(ErrorKind.BAD_ARGUMENT, "every cell to build needs its block: give Blocks "
                        + "({name = \"stone\", pos = " + LuaCodecs.literal(cell.pos()) + "}); a Pos alone only "
                        + "says where", null);
            }
            BuildStates.Resolved block = BuildStates.resolve(cell.block());
            canvas.put(new BuildTaskRecord.Target(block.state(), block.item(), cell.pos(), block.label()));
        }
        return canvas.targets();
    }

    /** 世界里这一格此刻是什么;区块没加载时读不到,是 null,不为了看一眼去生成区块。 */
    private static Function<BlockPos, BlockState> seen(ServerLevel level) {
        return pos -> level.isLoaded(pos) ? level.getBlockState(pos) : null;
    }
}
