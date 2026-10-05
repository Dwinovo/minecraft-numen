package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.blueprint.BlueprintStore;
import com.dwinovo.numen.core.build.BuildStates;
import com.dwinovo.numen.core.build.Built;
import com.dwinovo.numen.core.build.Canvas;
import com.dwinovo.numen.core.build.Changes;
import com.dwinovo.numen.core.build.Design;
import com.dwinovo.numen.core.build.Layout;
import com.dwinovo.numen.core.build.Placement;
import com.dwinovo.numen.core.task.build.BuildCompanionTask;
import com.dwinovo.numen.core.task.build.BuildOrder;
import com.dwinovo.numen.core.task.build.BuildSurvey;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Job;
import com.dwinovo.numen.sdk.LuaCodecs;
import com.dwinovo.numen.sdk.ServerCall;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 建造动世界的这一半:读一份蓝图文件摆在哪儿({@code numen.build.blueprint})、把一处手够得着的格变成要盖的样子
 * ({@code numen.build.place})、数还差什么({@code numen.build.diff})。
 *
 * <p>两条路是同一条:摆出一份施工图({@link Layout})→ 和世界比出要动的格({@link Changes})→ 没有要动的就不派活,有就交执行器,
 * 它只放站在原地够得着的格,每格轮到一次就收场。{@code numen.build.diff} 摆同一份施工图、比同一份差异,按执行器挑格的同一个判据
 * ({@link BuildSurvey})数,不派活。
 *
 * <p>一份蓝图摆在同一个维度、同一个落点、同一个朝向就是同一栋({@link Built}):再放一次按差异改,蓝图里已经没有、她从前放下的格
 * 也拆。一串格只是那几格,不算一栋房子。
 */
public final class BuildOps {

    private BuildOps() {}

    /** 一份蓝图文件摆在哪儿,连同它的尺寸、格数与用料:{@code numen.build.blueprint} 交回的值,{@code place} 与 {@code diff} 收它。 */
    @Doc("A blueprint file placed at a spot, as numen.build.blueprint returns it; numen.build.place and numen.build.diff "
            + "take it. It says only which file and where: the cells stay in the file.")
    public record Blueprint(@Doc("The file.") String blueprint,
                            @Doc("Where its lowest north-west corner goes.") BlockPos origin,
                            @Doc("Degrees clockwise: 0, 90, 180 or 270.") int rotation,
                            @Doc("How big the file is: x by y by z.") BlockPos size,
                            @Doc("How many cells it has.") int cells,
                            @Doc("Cells of the file that are liquids or blocks with no item to pay with: not built.")
                            int notBuilt,
                            @Doc("Items for all of it, most first.") Map<String, Integer> materials,
                            @Doc("Of those, what you are still short of, in survival; empty means you carry enough.")
                            Optional<Map<String, Integer>> shortOf) {}

    /**
     * 一份蓝图文件摆在 {@code origin}、转 {@code quarters} 个 90°:读一遍文件,交回名字、原点、度数,加上尺寸、格数与用料;生存模式下还有
     * 整份要的料她还缺多少。格子不交回,用到时 {@code numen.build.place} 与 {@code numen.build.diff} 再读文件。
     */
    public static Blueprint blueprint(NumenPlayer her, String name, BlockPos origin, int quarters) {
        Layout layout = load(her.serverLevel(), name, origin, quarters);
        Map<Item, Integer> cost = BuildBill.cost(layout.targets(), layout.cellNeeds().keySet());
        Vec3i size = layout.size();
        return new Blueprint(name, origin, quarters * 90, new BlockPos(size.getX(), size.getY(), size.getZ()),
                layout.targets().size(), layout.dropped(), BuildBill.items(cost),
                WorkProfile.of(her).freeMaterials() ? Optional.empty()
                        : Optional.of(BuildBill.items(BuildBill.shortOf(her, cost))));
    }

    /** 把这一处变成要盖的样子:够得着的放一遍;已经是这个样子就不派活,当场交回什么都没放。 */
    public static Job<BuildCompanionTask.Placed> place(ServerCall call, Design design) {
        NumenPlayer her = call.her();
        Planned plan = plan(her, design);
        if (plan.changes().none()) {
            return Job.done(BuildCompanionTask.Placed.NOTHING);
        }
        boolean consume = !WorkProfile.of(her).freeMaterials();
        Layout work = work(plan.layout(), plan.changes());
        long deadline = her.level().getGameTime() + BuildOrder.deadlineTicks(work.targets().size(), consume);
        // 蓝图文件一趟运不完是常态,分段施工;一串格是她自己画的,整份一次预检,缺料一格不放
        return Job.of(new BuildTaskRecord(call, deadline, work, consume, plan.site() != null, plan.site()));
    }

    /** 离要盖的样子还差什么:{@code numen.build.diff} 交回的值。 */
    @Doc("What a spot still lacks to look like a design, seen from where you stand.")
    public record Diff(@Doc("Cells still to do.") int left,
                       @Doc("Within reach to place now.") int reach,
                       @Doc("Up to " + LISTED_DIG + " cells holding another block to dig out first, nearest first.")
                       List<BlockPos> dig,
                       @Doc("Out of reach.") int far,
                       @Doc("The lowest, then nearest, of those out of reach: numen.move.to(d.next, {arrive = "
                               + "\"place\"}) gets within reach of it.") Optional<BlockPos> next,
                       @Doc("Cells holding another block with nothing of yours to put there.") int noStock,
                       @Doc("Cells that would not stay put yet: what holds them (the block below, the wall behind) is "
                               + "not built.") int unheld,
                       @Doc("Cells that differ and that you leave alone.") int skipped) {}

    /**
     * 这一处离要盖的样子还差什么,按她此刻站的地方数:还剩几格、几格够得着、几格要先挖开(给出最近的几格)、几格够不着
     * (给出最低最近的一格)。只读,当场回;数的判据与 {@link #place} 挑格的是同一个。
     */
    public static Diff diff(NumenPlayer her, Design design) {
        Planned plan = plan(her, design);
        if (plan.changes().none()) {
            return new Diff(0, 0, List.of(), 0, Optional.empty(), 0, 0, 0);
        }
        BuildTaskRecord record = new BuildTaskRecord("numen.build.diff", "", 0, work(plan.layout(), plan.changes()),
                !WorkProfile.of(her).freeMaterials(), plan.site() != null, plan.site());
        BuildSurvey.Tally tally = BuildSurvey.of(her, record).tally();
        return new Diff(tally.left(), tally.count(BuildSurvey.State.REACH),
                List.copyOf(tally.dig().subList(0, Math.min(LISTED_DIG, tally.dig().size()))), tally.far().size(),
                tally.far().isEmpty() ? Optional.empty() : Optional.of(tally.far().get(0)),
                tally.count(BuildSurvey.State.SHORT), tally.count(BuildSurvey.State.UNHELD),
                tally.count(BuildSurvey.State.SKIPPED));
    }

    /** {@code numen.build.diff} 列出几格要先挖开的:一次 {@code numen.work.dig} 交得完的量。 */
    private static final int LISTED_DIG = 16;

    /**
     * 摆好的一份施工图与它和世界的差异。一份蓝图在同一处已经盖过一栋,差异里带上该拆的格;第一次盖时这一栋还没有记录,差异
     * 就是整栋。一串格没有记录,差异就是这几格。
     *
     * @param site 盖的是哪一栋;一串格是 null
     */
    private record Planned(Layout layout, Changes changes, Built.Site site) {}

    /** 摆出施工图、比出差异。 */
    private static Planned plan(NumenPlayer her, Design design) {
        ServerLevel level = her.serverLevel();
        if (design.blueprint() == null) {
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
            Layout layout = canvas.layout();
            return new Planned(layout, Changes.between(layout.targets(), null, seen(level)), null);
        }
        Design.Blueprint bp = design.blueprint();
        int quarters = Placement.quarters(bp.rotation());
        Layout layout = load(level, bp.name(), bp.origin(), quarters);
        Built.Site site = new Built.Site(bp.name(), level.dimension().location(), bp.origin(), quarters);
        Built.Building was = Built.of(level.getServer()).at(site);
        return new Planned(layout, Changes.between(layout.targets(), was, seen(level)), site);
    }

    /** 读一份蓝图文件摆在这里;没有这个文件,说有哪些。 */
    private static Layout load(ServerLevel level, String name, BlockPos origin, int quarters) {
        List<String> files = BlueprintStore.list(level.getServer());
        if (!files.contains(name)) {
            throw new ApiError(ErrorKind.NOT_FOUND, "there is no blueprint file named " + name + (files.isEmpty()
                    ? "; the schematics folder has none" : "; the schematics folder has " + String.join(", ", files)),
                    null);
        }
        Layout layout = BlueprintStore.load(level, name, origin, quarters);
        if (layout.targets().isEmpty()) {
            throw new ApiError(ErrorKind.FAILED, "the blueprint file " + name + " has nothing to build", null);
        }
        return layout;
    }

    /** 交给执行器的施工图:要动的格,带上原图的尺寸、方块实体数据、摆设与料单。 */
    private static Layout work(Layout layout, Changes changes) {
        return new Layout(changes.work(), layout.size(), layout.blockEntityData(), layout.entities(),
                layout.cellNeeds(), layout.dropped());
    }

    /** 世界里这一格此刻是什么;区块没加载时读不到,是 null,不为了看一眼去生成区块。 */
    private static Function<BlockPos, BlockState> seen(ServerLevel level) {
        return pos -> level.isLoaded(pos) ? level.getBlockState(pos) : null;
    }
}
