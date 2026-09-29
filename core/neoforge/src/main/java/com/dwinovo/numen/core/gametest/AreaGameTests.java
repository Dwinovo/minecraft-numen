package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.AreaStore;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.PermissionStore;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 区域:{@code scan blocks --into} 把看到的团记成区域的部分,{@code area show} 列出来;{@code area refresh} 划掉被人换过的格;
 * 运算把结果存成新的一块;改主人规则点名的区域先问主人,主人拒绝则区域不变,没被点名的照常改;{@code scan blocks --in} 只在区域里找。
 * 全部从命令入口调。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class AreaGameTests {

    /** 区域批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_area")
    public static void prepareAreaBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 主人名下的区域(直接读存档,核对命令写进去的样子)。 */
    private static AreaStore areas(NumenPlayer companion) {
        return AreaStore.of(companion.getServer(), companion.getOwnerUuid());
    }

    private static String message(ToolRun run) {
        return JsonParser.parseString(run.reply()).getAsJsonObject().get("message").getAsString();
    }

    private static String box(BlockPos a, BlockPos b) {
        return a.getX() + "," + a.getY() + "," + a.getZ() + ".." + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /**
     * 扫进区域、再看区域:两块分开的紫珀块扫进 {@code purpur},回执给每团一个编号 {@code purpur/g1}、{@code purpur/g2};
     * {@code area show purpur} 一部分一行,格数、看到的方块、包围盒、此刻挖它许不许(自然方块放行)都在,和扫描回执对得上。
     */
    @GameTest(template = "floor16", timeoutTicks = 4000, batch = "numen_area")
    public static void scan_into_keeps_each_group_and_area_show_lists_the_parts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos far = helper.absolutePos(new BlockPos(11, 2, 11));
        BlockPos farTop = far.above();
        level.setBlockAndUpdate(near, Blocks.PURPUR_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(far, Blocks.PURPUR_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(farTop, Blocks.PURPUR_BLOCK.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_cartographer", new BlockPos(3, 2, 3), false);
        ToolRun scanned = scanInto(companion, 12, "minecraft:purpur_block", "purpur");
        ToolRun[] shown = new ToolRun[1];

        succeedWhen(helper, () -> {
            if (shown[0] == null) {
                helper.assertTrue(scanned.reply() != null, "the scan has not replied");
                helper.assertTrue(message(scanned).contains("added to area purpur as g1 to g2"),
                        "the scan does not say what it added: " + scanned.reply());
                var nearGroup = groupHolding(groupsIn(scanned.reply()), near);
                var farGroup = groupHolding(groupsIn(scanned.reply()), far);
                helper.assertTrue(nearGroup != null && "purpur/g1".equals(nearGroup.get("id").getAsString())
                                && farGroup != null && "purpur/g2".equals(farGroup.get("id").getAsString()),
                        "the groups did not get their ids in the area: " + scanned.reply());
                shown[0] = command(companion, "area show purpur");
            }
            helper.assertTrue(shown[0].succeeded(), "area show failed: " + shown[0].reply());
            String said = message(shown[0]);
            helper.assertTrue(said.startsWith("area purpur in minecraft:overworld: 2 part(s) (g1, g2), 3 cells"),
                    "the head does not sum the area up: " + said);
            JsonObject g2 = groupHolding(groupsIn(shown[0].reply()), far);
            helper.assertTrue(g2 != null && "purpur/g2".equals(g2.get("id").getAsString())
                            && g2.get("cells").getAsInt() == 2
                            && g2.getAsJsonObject("blocks").get("minecraft:purpur_block").getAsInt() == 2
                            && box(far, farTop).equals(g2.get("box").getAsString())
                            && "allow".equals(g2.get("permission").getAsString()),
                    "the part does not show its cells, blocks, box and permission: " + g2);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 复核:三格石英块扫进 {@code quartz},其中一格被人换成了泥土;{@code area refresh quartz} 说划掉了 1 格,区域里剩两格,
     * 再复核一次什么都不改。
     */
    @GameTest(template = "floor16", timeoutTicks = 4000, batch = "numen_area")
    public static void area_refresh_strikes_off_the_cells_someone_changed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = List.of(helper.absolutePos(new BlockPos(6, 2, 6)), helper.absolutePos(new BlockPos(7, 2, 6)),
                helper.absolutePos(new BlockPos(8, 2, 6)));
        cells.forEach(p -> level.setBlockAndUpdate(p, Blocks.QUARTZ_BLOCK.defaultBlockState()));
        NumenPlayer companion = spawnAt(helper, "gametest_auditor", new BlockPos(3, 2, 3), false);
        ToolRun scanned = scanInto(companion, 10, "minecraft:quartz_block", "quartz");
        ToolRun[] refreshed = new ToolRun[2];

        succeedWhen(helper, () -> {
            if (refreshed[0] == null) {
                helper.assertTrue(scanned.reply() != null, "the scan has not replied");
                helper.assertTrue(areas(companion).get("quartz").cells().size() == 3, "the scan did not keep 3 cells");
                level.setBlockAndUpdate(cells.get(1), Blocks.DIRT.defaultBlockState());
                refreshed[0] = command(companion, "area refresh quartz");
                refreshed[1] = command(companion, "area refresh quartz");
            }
            helper.assertTrue(refreshed[0].succeeded() && message(refreshed[0]).startsWith(
                            "struck off 1 of the 3 scanned cells of quartz"),
                    "refresh does not say what it struck off: " + refreshed[0].reply());
            Area left = areas(companion).get("quartz");
            helper.assertTrue(left.cells().size() == 2 && !left.cells().contains(cells.get(1)),
                    "the changed cell is still in the area: " + left);
            helper.assertTrue(message(refreshed[1]).contains("all 2 scanned cells of quartz still hold what was seen"),
                    "a second refresh changed something: " + refreshed[1].reply());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 运算:框一个 3×1×3 的盒子、加一个点;{@code minus} 去掉那一点存成新的一块,{@code union} 把两块并起来(右边的部分按左边的
     * 计数续号:左边发过 p1,并进来的点是 p2),{@code grow} 外扩一格,{@code center} 取中心那一格;每个结果都是一块新区域,格数对得上,
     * 点名的原区域不变,结果不覆盖已有的区域。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_area")
    public static void area_operations_keep_their_results_as_new_areas(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_surveyor_ops", new BlockPos(3, 2, 3), false);
        BlockPos a = helper.absolutePos(new BlockPos(5, 3, 5));
        BlockPos b = helper.absolutePos(new BlockPos(7, 3, 7));
        BlockPos mid = helper.absolutePos(new BlockPos(6, 3, 6));
        BlockPos out = helper.absolutePos(new BlockPos(12, 3, 12));
        List<ToolRun> runs = List.of(
                command(companion, "area new yard"),
                command(companion, "area add yard --box " + box(a, b)),
                command(companion, "area add yard --at " + xyz(mid)),
                command(companion, "area minus ring yard yard/p1"),
                command(companion, "area new post"),
                command(companion, "area add post --at " + xyz(out)),
                command(companion, "area union both ring post"),
                command(companion, "area grow halo post 1"),
                command(companion, "area center heart yard"));
        for (ToolRun run : runs) {
            helper.assertTrue(run.succeeded(), "an area command failed: " + run.reply());
        }
        AreaStore store = areas(companion);
        helper.assertTrue(store.get("yard").cells().size() == 9 && store.get("yard").parts().size() == 2,
                "the source area changed: " + store.get("yard"));
        Area ring = store.get("ring");
        helper.assertTrue(ring.cells().size() == 8 && !ring.cells().contains(mid)
                        && ring.parts().stream().map(Area.Part::id).toList().equals(List.of("b1")),
                "minus did not take the middle out of the box: " + ring);
        Area both = store.get("both");
        helper.assertTrue(both.cells().size() == 9 && both.cells().contains(out)
                        && both.parts().stream().map(Area.Part::id).toList().equals(List.of("b1", "p2")),
                "union did not keep both areas' parts, the right one numbered on from the left's count: " + both);
        helper.assertTrue(store.get("halo").cells().size() == 27, "grow by one is not the 3×3×3 cube around the post");
        Area heart = store.get("heart");
        helper.assertTrue(heart.cells().size() == 1 && heart.cells().contains(mid), "center is not the middle cell");
        ToolRun taken = command(companion, "area union ring yard");
        helper.assertTrue(!taken.succeeded() && taken.reply().contains("there is already an area named ring"),
                "an operation overwrote an existing area: " + taken.reply());
        CompanionFactory.despawn(level.getServer(), companion);
        helper.succeed();
    }

    /**
     * 改区域过权限层:主人写了 {@code deny break(area:house)}。她删 {@code house} 先问主人、这次调用悬着;主人拒绝,回执说没做、
     * 理由是主人拒绝,区域还在。她新建、改一块没被主人规则点名的 {@code ores} 照常,不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 4000, batch = "numen_area")
    public static void editing_an_area_the_owners_rules_name_asks_and_a_refusal_leaves_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_steward", new BlockPos(3, 2, 3), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_landlord");
        BlockPos a = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos b = helper.absolutePos(new BlockPos(9, 5, 9));
        helper.assertTrue(command(companion, "area new house").succeeded()
                        && command(companion, "area add house --box " + box(a, b)).succeeded(),
                "the house area could not be made before any rule named it");
        PermissionStore.of(owner.getServer(), owner.getUUID()).add(Verdict.Kind.DENY, Rule.parse("break(area:house)"));
        ToolRun delete = command(companion, "area delete house");
        ConsentDesk desk = ConsentDesk.of(companion);
        boolean[] answered = new boolean[1];
        ToolRun[] free = new ToolRun[2];

        succeedWhen(helper, () -> {
            if (!answered[0]) {
                var pending = desk.pending();
                helper.assertTrue(pending != null, "deleting an area the owner's rules name did not ask the owner");
                helper.assertTrue(delete.reply() == null, "the call did not wait for the owner");
                helper.assertTrue(areas(companion).get("house") != null, "the house area was deleted before the answer");
                answered[0] = desk.answer(pending.id(), ConsentAnswer.Decision.DENY, "");
                free[0] = command(companion, "area new ores");
                free[1] = command(companion, "area add ores --at " + xyz(a));
            }
            helper.assertTrue(delete.reply() != null, "the refused call has not replied");
            helper.assertTrue(!delete.succeeded() && delete.reply().contains("did not run area delete house"),
                    "the refusal does not say what did not run: " + delete.reply());
            Area house = areas(companion).get("house");
            helper.assertTrue(house != null && house.cells().size() == 5L * 4 * 5, "the house area changed: " + house);
            helper.assertTrue(free[0].succeeded() && free[1].succeeded() && desk.pending() == null,
                    "an area no rule names did not change freely: " + free[0].reply() + " / " + free[1].reply());
            helper.assertTrue(areas(companion).get("ores").cells().size() == 1, "the free area was not changed");
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
        });
    }

    /**
     * 只在区域里找:两块海晶灯,框一个盒子罩住其中一块;{@code scan blocks --in lamp} 只找到盒子里那一块,盒子外的不在回执里。
     */
    @GameTest(template = "floor16", timeoutTicks = 4000, batch = "numen_area")
    public static void scan_blocks_in_an_area_looks_only_there(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos inside = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos outside = helper.absolutePos(new BlockPos(11, 2, 11));
        level.setBlockAndUpdate(inside, Blocks.SEA_LANTERN.defaultBlockState());
        level.setBlockAndUpdate(outside, Blocks.SEA_LANTERN.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_lamplighter", new BlockPos(3, 2, 3), false);
        helper.assertTrue(command(companion, "area new lamp").succeeded()
                        && command(companion, "area add lamp --box " + box(inside.offset(-1, -1, -1),
                                inside.offset(1, 1, 1))).succeeded(), "the lamp area could not be framed");
        ToolRun scanned = command(companion, "scan blocks 16 minecraft:sea_lantern --in lamp");

        succeedWhen(helper, () -> {
            helper.assertTrue(scanned.reply() != null, "the scan has not replied");
            var groups = groupsIn(scanned.reply());
            helper.assertTrue(groups.size() == 1 && groupHolding(groups, inside) != null
                            && groupHolding(groups, outside) == null && message(scanned).contains("inside area lamp"),
                    "the scan looked outside the area: " + scanned.reply());
            helper.assertTrue(!groups.get(0).getAsJsonObject().has("id"), "a scan that only looks gave an id");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
