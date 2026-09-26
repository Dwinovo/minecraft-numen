package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.blueprint.BlueprintStore;
import com.dwinovo.numen.core.build.Built;
import com.dwinovo.numen.core.build.Canvas;
import com.dwinovo.numen.core.build.Changes;
import com.dwinovo.numen.core.build.Designs;
import com.dwinovo.numen.core.build.Layout;
import com.dwinovo.numen.core.build.Placement;
import com.dwinovo.numen.core.build.Primitive;
import com.dwinovo.numen.core.task.build.BuildOrder;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 建造动世界的这一半:当场执行一个原语、{@code build at} 把一处变成施工图的样子、列出建成的房子。
 *
 * <p>两条派活的路是同一条:摆出一份施工图({@link Layout})→ 和世界比出要动的格({@link Changes})→ 没有要动的就不派活,
 * 有就交同一个执行器。当场执行就是"只有一步、摆在世界坐标上、不算一栋房子"的那一种。
 */
public final class BuildOps {

    private BuildOps() {}

    /** 当场执行一个原语:坐标是世界坐标,底子是世界({@code copy} 抄的是已经立着的那一片)。 */
    public static void now(ServerSource src, Primitive primitive, CommandArgs args) {
        ServerLevel level = src.companion().serverLevel();
        Canvas canvas = new Canvas(Canvas.Ground.of(level));
        primitive.draw(args, canvas);
        Layout layout = canvas.laid(Placement.IN_PLACE);
        dispatch(src, layout, Changes.between(layout.targets(), null, seen(level)), false, null,
                "it already stands like that; nothing to change");
    }

    /**
     * 把这一处变成施工图的样子:同一份施工图、同一个维度、同一个落点已经有一栋,就是改它(差异里带上该拆的格);
     * 否则是在这里盖一栋新的。第一次盖时这一栋还没有记录,差异就是整栋。
     *
     * @param name 设计名或蓝图文件名
     */
    public static void at(ServerSource src, String name, BlockPos anchor, int quarters) {
        NumenPlayer her = src.companion();
        ServerLevel level = her.serverLevel();
        Placement at = new Placement(anchor, quarters);
        boolean file = Designs.kindOf(level.getServer(), name) == Designs.Kind.BLUEPRINT_FILE;
        Layout layout = !file
                ? Designs.load(level.getServer(), name).drawn().laid(at)
                : BlueprintStore.load(level, name, anchor, quarters);
        if (layout.targets().isEmpty()) {
            src.reply(TaskResult.fail(name + " has nothing to build yet").toJson());
            return;
        }
        Built.Site site = new Built.Site(name, level.dimension().location(), anchor, at.quarters());
        Built.Building was = Built.of(level.getServer()).at(site);
        String where = anchor.getX() + " " + anchor.getY() + " " + anchor.getZ();
        // 蓝图文件一趟运不完是常态,分段施工 + 精确续建;设计是她自己写的一栋,整份一次预检,缺料一格不放
        dispatch(src, layout, Changes.between(layout.targets(), was, seen(level)), file, site,
                (was == null ? name : was.name()) + " at " + where + " already looks like " + name
                        + "; nothing to change");
    }

    /** 建成的房子,按盖下去的先后,每栋一行:名字、照什么盖的、在哪、朝向、何时、谁盖、记着几格。 */
    public static String built(MinecraftServer server, CommandArgs args) {
        List<String> rows = new ArrayList<>();
        for (Built.Building b : Built.of(server).all()) {
            String source;
            if (Designs.exists(server, b.source())) {
                source = "design " + b.source();
            } else if (BlueprintStore.list(server).contains(b.source())) {
                source = "blueprint file " + b.source();
            } else {
                source = b.source() + ", which has since been deleted";
            }
            BlockPos a = b.anchor();
            rows.add("  " + b.name() + " — " + source + ", " + b.dimension().getPath() + " at " + a.getX() + " "
                    + a.getY() + " " + a.getZ() + (b.quarters() == 0 ? "" : " turned " + (b.quarters() * 90))
                    + ", built on day " + day(b.builtAt()) + " by " + b.builder()
                    + (b.changedAt() == b.builtAt() ? "" : ", last changed on day " + day(b.changedAt()))
                    + ", " + b.cells().size() + " block(s) on its record");
        }
        String head = rows.isEmpty()
                ? "Nothing has been built with build at yet."
                : "Built with build at (build at the same design, dimension and spot changes that building):";
        return new Listing(head, rows, "", "build built").result(args).toJson();
    }

    /** 游戏里的第几天(从 1 数)。 */
    private static long day(long gameTime) {
        return gameTime / 24000L + 1;
    }

    /** 世界里这一格此刻是什么;区块没加载时读不到,是 null,不为了看一眼去生成区块。 */
    private static Function<BlockPos, BlockState> seen(ServerLevel level) {
        return pos -> level.isLoaded(pos) ? level.getBlockState(pos) : null;
    }

    /**
     * 有要动的格就派活,没有就当场说这里已经是那个样子。交给执行器的是施工图的全部格加上要拆的格;材料记账随能力画像
     * (免耗材想建就建,否则开工前整批预检、逐格真扣)。
     */
    private static void dispatch(ServerSource src, Layout layout, Changes changes, boolean partial,
                                 Built.Site site, String already) {
        if (changes.none()) {
            src.reply(TaskResult.ok(already).toJson());
            return;
        }
        NumenPlayer her = src.companion();
        boolean consume = !WorkProfile.of(her).freeMaterials();
        Layout work = new Layout(changes.work(), layout.size(), layout.blockEntityData(), layout.entities(),
                layout.cellNeeds(), layout.dropped());
        long deadline = her.level().getGameTime() + BuildOrder.deadlineTicks(work.targets().size(), consume);
        TaskDispatch.setTask(src, new BuildTaskRecord(src, deadline, work, consume, partial, site));
    }
}
