package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.cli.Building;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.blueprint.BlueprintStore;
import com.dwinovo.numen.core.build.BuildStates;
import com.dwinovo.numen.core.build.Built;
import com.dwinovo.numen.core.build.Canvas;
import com.dwinovo.numen.core.build.Changes;
import com.dwinovo.numen.core.build.Layout;
import com.dwinovo.numen.core.build.Placement;
import com.dwinovo.numen.core.task.build.BuildOrder;
import com.dwinovo.numen.core.task.build.BuildSurvey;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 一份蓝图文件摆在 {@code origin}、转 {@code quarters} 个 90°:读一遍文件,交回那张表(名字、原点、度数,加上尺寸、格数与用料;
     * 生存模式下还有整份要的料她还缺多少)。格子不交回,用到时 {@code numen.build.place} 与 {@code numen.build.diff} 再读文件。
     */
    public static String blueprint(NumenPlayer her, String name, BlockPos origin, int quarters) {
        Layout layout = load(her.serverLevel(), name, origin, quarters);
        Map<Item, Integer> cost = BuildBill.cost(layout.targets(), layout.cellNeeds().keySet());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("blueprint", name);
        data.put("origin", Shapes.value(origin));
        data.put("rotation", quarters * 90);
        Vec3i size = layout.size();
        data.put("size", Shapes.value(new BlockPos(size.getX(), size.getY(), size.getZ())));
        data.put("cells", layout.targets().size());
        data.put("materials", BuildBill.summarize(cost).get("items"));
        String text = name + " at " + origin.getX() + " " + origin.getY() + " " + origin.getZ()
                + (quarters == 0 ? "" : ", turned " + quarters * 90) + ": " + size.getX() + "x" + size.getY() + "x"
                + size.getZ() + ", " + layout.targets().size() + " cells, needs " + BuildBill.sum(cost) + " items across "
                + cost.size() + " kinds — " + BuildBill.topLine(cost);
        if (layout.dropped() > 0) {
            text += "; " + layout.dropped() + " cell(s) of the file are liquids or blocks with no item to pay with, "
                    + "and are not built";
        }
        if (!WorkProfile.of(her).freeMaterials()) {
            Map<Item, Integer> shortOf = BuildBill.shortOf(her, cost);
            data.put("short_of", BuildBill.summarize(shortOf).get("items"));
            text += shortOf.isEmpty() ? ". You carry enough for all of it."
                    : ". For all of it you are still short " + BuildBill.topLine(shortOf) + ".";
        }
        return TaskResult.ok(text, data).toJson();
    }

    /** 把这一处变成要盖的样子:够得着的放一遍。 */
    public static void place(ServerSource src, Building building) {
        Planned plan = plan(src.companion(), building);
        if (plan.changes().none()) {
            src.reply(TaskResult.ok(plan.already(), Map.of("placed", 0, "left", 0)).toJson());
            return;
        }
        NumenPlayer her = src.companion();
        boolean consume = !WorkProfile.of(her).freeMaterials();
        Layout work = work(plan.layout(), plan.changes());
        long deadline = her.level().getGameTime() + BuildOrder.deadlineTicks(work.targets().size(), consume);
        // 蓝图文件一趟运不完是常态,分段施工;一串格是她自己画的,整份一次预检,缺料一格不放
        TaskDispatch.setTask(src, new BuildTaskRecord(src, deadline, work, consume, plan.site() != null, plan.site()));
    }

    /**
     * 这一处离要盖的样子还差什么,按她此刻站的地方数:还剩几格、几格够得着、几格要先挖开(给出最近的几格)、几格够不着
     * (给出最低最近的一格)。只读,当场回;数的判据与 {@link #place} 挑格的是同一个。
     */
    public static String diff(NumenPlayer her, Building building) {
        Planned plan = plan(her, building);
        Map<String, Object> data = new LinkedHashMap<>();
        if (plan.changes().none()) {
            data.put("left", 0);
            data.put("reach", 0);
            data.put("dig", List.of());
            data.put("far", 0);
            return TaskResult.ok(plan.already(), data).toJson();
        }
        BuildTaskRecord record = new BuildTaskRecord("numen.build.diff", "", 0, work(plan.layout(), plan.changes()),
                !WorkProfile.of(her).freeMaterials(), plan.site() != null, plan.site());
        BuildSurvey.Tally tally = BuildSurvey.of(her, record).tally();
        data.put("left", tally.left());
        data.put("reach", tally.count(BuildSurvey.State.REACH));
        data.put("dig", tally.dig().subList(0, Math.min(LISTED_DIG, tally.dig().size())).stream()
                .map(Shapes::pos).toList());
        data.put("far", tally.far().size());
        if (!tally.far().isEmpty()) {
            data.put("next", Shapes.pos(tally.far().get(0)));
        }
        data.put("short", tally.count(BuildSurvey.State.SHORT));
        data.put("unheld", tally.count(BuildSurvey.State.UNHELD));
        data.put("skipped", tally.count(BuildSurvey.State.SKIPPED));
        String text = tally.left() == 0
                ? "nothing left to do: every cell of " + plan.name() + " that differs is one you leave alone ("
                        + tally.count(BuildSurvey.State.SKIPPED) + ")"
                : tally.left() + " cell(s) of " + plan.name() + " still to do: " + tally.count(BuildSurvey.State.REACH)
                        + " within reach to place now, " + tally.dig().size() + " to dig out first, "
                        + tally.far().size() + " out of reach, " + tally.count(BuildSurvey.State.SHORT)
                        + " holding another block with nothing of yours to put there, "
                        + tally.count(BuildSurvey.State.UNHELD) + " that would not stay put yet";
        return TaskResult.ok(text, data).toJson();
    }

    /** {@code numen.build.diff} 列出几格要先挖开的:一次 {@code numen.work.dig} 交得完的量。 */
    private static final int LISTED_DIG = 16;

    /**
     * 摆好的一份施工图与它和世界的差异。一份蓝图在同一处已经盖过一栋,差异里带上该拆的格;第一次盖时这一栋还没有记录,差异
     * 就是整栋。一串格没有记录,差异就是这几格。
     *
     * @param name 回执里怎么说它:蓝图的文件名,或"the N cells"
     * @param site 盖的是哪一栋;一串格是 null
     */
    private record Planned(String name, Layout layout, Changes changes, Built.Site site) {

        /** 已经是这个样子时的那句话。 */
        String already() {
            return name + " already stands like that; nothing to change";
        }
    }

    /** 摆出施工图、比出差异。 */
    private static Planned plan(NumenPlayer her, Building building) {
        ServerLevel level = her.serverLevel();
        if (building.blueprint() == null) {
            Canvas canvas = new Canvas();
            for (Building.Cell cell : building.cells()) {
                if (cell.block() == null) {
                    throw new IllegalArgumentException("every cell to build needs its block: give Blocks ({name = "
                            + "\"stone\", pos = " + Shapes.literal(cell.pos()) + "}); a Pos alone only says where");
                }
                BuildStates.Resolved block = BuildStates.resolve(cell.block());
                canvas.put(new BuildTaskRecord.Target(block.state(), block.item(), cell.pos(), block.label()));
            }
            Layout layout = canvas.layout();
            return new Planned("the " + building.cells().size() + " cell(s)", layout,
                    Changes.between(layout.targets(), null, seen(level)), null);
        }
        Building.Blueprint bp = building.blueprint();
        int quarters = Placement.quarters(bp.rotation());
        Layout layout = load(level, bp.name(), bp.origin(), quarters);
        Built.Site site = new Built.Site(bp.name(), level.dimension().location(), bp.origin(), quarters);
        Built.Building was = Built.of(level.getServer()).at(site);
        return new Planned(bp.name() + " at " + bp.origin().getX() + " " + bp.origin().getY() + " "
                + bp.origin().getZ(), layout, Changes.between(layout.targets(), was, seen(level)), site);
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
