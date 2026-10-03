package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.AreaStore;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.tools.AreaText;
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
 * 区域:{@code scan.blocks --into} 把看到的团记成区域的部分,{@code area.show} 列出来;{@code area.refresh} 划掉被人换过的格;
 * 运算把结果存成新的一块;改主人规则点名的区域先问主人,主人拒绝则区域不变,没被点名的照常改;{@code scan.blocks --in} 只在区域里找。
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

    /** 回执里一个长方体的写法:两个对角 {@code x,y,z x,y,z}。 */
    private static com.google.gson.JsonArray box(BlockPos a, BlockPos b) {
        com.google.gson.JsonArray corners = new com.google.gson.JsonArray();
        corners.add(com.dwinovo.numen.cli.Shapes.pos(a));
        corners.add(com.dwinovo.numen.cli.Shapes.pos(b));
        return corners;
    }

    /** 一个长方体的两个对角写成脚本里的 {@code {from, to}},各是一格 Pos。 */
    private static String luaBox(BlockPos a, BlockPos b) {
        return "{" + xyz(a) + ", " + xyz(b) + "}";
    }

    /**
     * 扫进区域、再看区域:两块分开的紫珀块扫进 {@code purpur},回执给每团一个编号 {@code purpur/g1}、{@code purpur/g2};
     * {@code area.show purpur} 一部分一行,格数、看到的方块、包围盒、此刻挖它许不许(自然方块放行)都在,和扫描回执对得上。
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
                shown[0] = lua(companion, "area.show(\"purpur\")");
            }
            helper.assertTrue(shown[0].succeeded(), "area show failed: " + shown[0].reply());
            String said = message(shown[0]);
            helper.assertTrue(said.startsWith("area purpur in minecraft:overworld: 2 part(s) (g1, g2), 3 cells"),
                    "the head does not sum the area up: " + said);
            JsonObject g2 = groupHolding(groupsIn(shown[0].reply()), far);
            helper.assertTrue(g2 != null && "purpur/g2".equals(g2.get("id").getAsString())
                            && g2.get("count").getAsInt() == 2
                            && g2.getAsJsonObject("blocks").get("minecraft:purpur_block").getAsInt() == 2
                            && box(far, farTop).equals(g2.get("box"))
                            && "allow".equals(g2.get("permission").getAsString()),
                    "the part does not show its cells, blocks, box and permission: " + g2);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 逐部分取用:两团紫珀块扫进 {@code beads},{@code area.parts beads} 一行一个部分名,别的什么都没有。{@code area.has} 的成败
     * 就是答案,按 {@code work.dig} 的判据在活世界里问:扫来的格还是紫珀块才算。远处那团两格,上面那格换成石头,
     * {@code area.has beads/g2} 仍成功、说还剩一格;下面那格也挖掉,它就失败——立着的石头不是扫到的方块;整块区域还有近处那团,
     * 照样成功。
     */
    @GameTest(template = "floor16", timeoutTicks = 4000, batch = "numen_area")
    public static void area_parts_names_each_part_and_area_has_answers_by_success(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos far = helper.absolutePos(new BlockPos(11, 2, 11));
        BlockPos farTop = far.above();
        level.setBlockAndUpdate(near, Blocks.PURPUR_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(far, Blocks.PURPUR_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(farTop, Blocks.PURPUR_BLOCK.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_bead_counter", new BlockPos(3, 2, 3), false);
        ToolRun scanned = scanInto(companion, 12, "minecraft:purpur_block", "beads");

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(scanned.done() && scanned.succeeded(),
                        "the scan has not replied: " + scanned.reply()))
                .thenExecute(() -> {
                    ToolRun parts = lua(companion, "area.parts(\"beads\")");
                    helper.assertTrue(parts.succeeded()
                                    && message(parts).lines().toList().equals(List.of("beads/g1", "beads/g2")),
                            "area parts does not list one part name per line: " + parts.reply());
                    ToolRun whole = lua(companion, "area.has(\"beads/g2\")");
                    helper.assertTrue(whole.succeeded()
                                    && message(whole).startsWith("beads/g2 has 2 cell(s) left to dig"),
                            "area has does not count the two purpur blocks of g2: " + whole.reply());

                    level.setBlockAndUpdate(farTop, Blocks.STONE.defaultBlockState());
                    ToolRun changed = lua(companion, "area.has(\"beads/g2\")");
                    helper.assertTrue(changed.succeeded() && dataIn(changed.reply()).get("left").getAsInt() == 1
                                    && dataIn(changed.reply()).get("nearest").equals(com.dwinovo.numen.cli.Shapes.pos(far))
                                    && message(changed).startsWith(
                                    "beads/g2 has 1 cell(s) left to dig, the nearest at " + far.getX() + "," + far.getY()
                                            + "," + far.getZ()),
                            "area has counts a cell that no longer holds the scanned block: " + changed.reply());

                    level.setBlockAndUpdate(far, Blocks.AIR.defaultBlockState());
                    ToolRun gone = lua(companion, "area.has(\"beads/g2\")");
                    helper.assertTrue(gone.succeeded() && !dataIn(gone.reply()).get("has").getAsBoolean()
                                    && message(gone).startsWith("beads/g2 has nothing left to dig"),
                            "area has does not answer false once nothing of g2 is left: " + gone.reply());
                    ToolRun rest = lua(companion, "area.has(\"beads\")");
                    helper.assertTrue(rest.succeeded() && message(rest).startsWith("beads has 1 cell(s) left to dig"),
                            "area has does not see the part still standing: " + rest.reply());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * {@code --into} 点名一块还没有的区域:一步扫进去,区域当场新建(像 shell 的 {@code >}),回执说新建了它、加成了 g1 到 g2;
     * 存档里真有这块区域、两部分。再放一块、再扫进同一块:这回是往已有的区域里续(g3),回执不再说新建。
     */
    @GameTest(template = "floor16", timeoutTicks = 4000, batch = "numen_area")
    public static void scan_into_a_missing_area_makes_it_and_says_so(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos far = helper.absolutePos(new BlockPos(11, 2, 11));
        BlockPos later = helper.absolutePos(new BlockPos(5, 2, 11));
        level.setBlockAndUpdate(near, Blocks.PURPUR_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(far, Blocks.PURPUR_BLOCK.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_new_mapper", new BlockPos(3, 2, 3), false);
        helper.assertTrue(areas(companion).get("fresh") == null, "the area fresh is there before the scan");
        ToolRun first = lua(companion, "scan.blocks(\"minecraft:purpur_block\", {into = \"fresh\", radius = 12})");
        ToolRun[] second = new ToolRun[1];

        succeedWhen(helper, () -> {
            if (second[0] == null) {
                helper.assertTrue(first.reply() != null, "the first scan has not replied");
                helper.assertTrue(first.succeeded() && message(first).contains(
                                "added to the new area fresh (made just now) as g1 to g2"),
                        "the scan does not say it made the area: " + first.reply());
                helper.assertTrue(areas(companion).get("fresh") != null
                                && areas(companion).get("fresh").parts().size() == 2,
                        "the area fresh was not made with two parts: " + areas(companion).get("fresh"));
                level.setBlockAndUpdate(later, Blocks.PURPUR_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(near, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(far, Blocks.AIR.defaultBlockState());
                second[0] = lua(companion, "scan.blocks(\"minecraft:purpur_block\", {into = \"fresh\", radius = 12})");
            }
            helper.assertTrue(second[0].reply() != null, "the second scan has not replied");
            helper.assertTrue(second[0].succeeded() && message(second[0]).contains("added to area fresh as g3")
                            && !message(second[0]).contains("new area"),
                    "the second scan did not add to the area it made: " + second[0].reply());
            helper.assertTrue(areas(companion).get("fresh").parts().size() == 3,
                    "the area fresh does not have three parts: " + areas(companion).get("fresh"));
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 64 团分开的红色下界砖(地板里隔一格一块)。只是看:第一页不超过一团一行清单的那一页({@link AreaText#PAGE_BYTES}),说一共
     * 64 团、下一页怎么取。扫进区域:回执只列最近 5 团,抬头说 64 团都加进了哪块区域、全部用 area.show 看;区域里真有 64 部分;
     * area.show 同样一页一页地列。
     */
    @GameTest(template = "floor16", timeoutTicks = 4000, batch = "numen_area")
    public static void a_long_scan_comes_in_short_pages_and_into_an_area_as_a_summary(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x < 16; x += 2) {
            for (int z = 1; z < 16; z += 2) {
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 1, z)), Blocks.RED_NETHER_BRICKS.defaultBlockState());
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_grid_reader", new BlockPos(8, 2, 8), false);
        ToolRun look = lua(companion, "scan.blocks(\"minecraft:red_nether_bricks\", {radius = 12})");
        ToolRun[] kept = new ToolRun[1];
        ToolRun[] shown = new ToolRun[1];

        succeedWhen(helper, () -> {
            if (kept[0] == null) {
                helper.assertTrue(look.reply() != null, "the scan has not replied");
                String page = message(look);
                helper.assertTrue(look.succeeded() && page.startsWith("64 group(s)")
                                && page.contains(" of 64. Call it again with page = 2 to continue.]"),
                        "the first page does not say how many there are and how to go on: " + page);
                int bytes = page.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                helper.assertTrue(bytes <= AreaText.PAGE_BYTES + 200, "the first page is " + bytes + " bytes");
                kept[0] = lua(companion, "scan.blocks(\"minecraft:red_nether_bricks\", {into = \"grid\", radius = 12})");
            }
            helper.assertTrue(kept[0].reply() != null, "the scan into grid has not replied");
            String summary = message(kept[0]);
            // 话里只列最近五团;交给程序的数据是全部 64 团(数据不分页,脚本要几团自己取)
            helper.assertTrue(kept[0].succeeded() && summary.startsWith("64 group(s)")
                            && summary.contains("as g1 to g64") && summary.contains("the nearest 5 follow")
                            && summary.contains("area.show(\"grid\")")
                            && summary.lines().filter(l -> l.startsWith("{")).count() == 5
                            && groupsIn(kept[0].reply()).size() == 64,
                    "the reply is not a summary with the nearest five: " + summary);
            helper.assertTrue(areas(companion).get("grid").parts().size() == 64,
                    "the area does not hold all 64 groups");
            if (shown[0] == null) {
                shown[0] = lua(companion, "area.show(\"grid\")");
            }
            String parts = message(shown[0]);
            helper.assertTrue(shown[0].succeeded() && parts.contains(" of 64. Call it again with page = 2"),
                    "area show does not page the 64 parts: " + parts);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 复核:三格石英块扫进 {@code quartz},其中一格被人换成了泥土;{@code area.refresh quartz} 说划掉了 1 格,区域里剩两格,
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
                refreshed[0] = lua(companion, "area.refresh(\"quartz\")");
                refreshed[1] = lua(companion, "area.refresh(\"quartz\")");
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
                lua(companion, "area.new(\"yard\")"),
                lua(companion, "area.add(\"yard\", {box = " + luaBox(a, b) + "})"),
                lua(companion, "area.add(\"yard\", {at = " + xyz(mid) + "})"),
                lua(companion, "area.minus(\"ring\", \"yard\", \"yard/p1\")"),
                lua(companion, "area.new(\"post\")"),
                lua(companion, "area.add(\"post\", {at = " + xyz(out) + "})"),
                lua(companion, "area.union(\"both\", \"ring\", \"post\")"),
                lua(companion, "area.grow(\"halo\", \"post\", {by = 1})"),
                lua(companion, "area.center(\"heart\", \"yard\")"));
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
        ToolRun taken = lua(companion, "area.union(\"ring\", \"yard\")");
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
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_landlord");
        BlockPos a = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos b = helper.absolutePos(new BlockPos(9, 5, 9));
        helper.assertTrue(lua(companion, "area.new(\"house\")").succeeded()
                        && lua(companion, "area.add(\"house\", {box = " + luaBox(a, b) + "})").succeeded(),
                "the house area could not be made before any rule named it");
        PermissionStore.of(owner.getServer(), owner.getUUID()).add(Verdict.Kind.DENY, Rule.parse("break(area:house)"));
        ToolRun delete = lua(companion, "area.delete(\"house\")");
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
                free[0] = lua(companion, "area.new(\"ores\")");
                free[1] = lua(companion, "area.add(\"ores\", {at = " + xyz(a) + "})");
            }
            helper.assertTrue(delete.reply() != null, "the refused call has not replied");
            helper.assertTrue(!delete.succeeded() && delete.outcome().contains("did not run area.delete(\"house\")"),
                    "the refusal does not say what did not run: " + delete.reply());
            Area house = areas(companion).get("house");
            helper.assertTrue(house != null && house.cells().size() == 5L * 4 * 5, "the house area changed: " + house);
            helper.assertTrue(free[0].succeeded() && free[1].succeeded() && desk.pending() == null,
                    "an area no rule names did not change freely: " + free[0].reply() + " / " + free[1].reply());
            helper.assertTrue(areas(companion).get("ores").cells().size() == 1, "the free area was not changed");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 只在区域里找:两块海晶灯,框一个盒子罩住其中一块;{@code scan.blocks --in lamp} 只找到盒子里那一块,盒子外的不在回执里。
     */
    @GameTest(template = "floor16", timeoutTicks = 4000, batch = "numen_area")
    public static void scan_blocks_in_an_area_looks_only_there(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos inside = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos outside = helper.absolutePos(new BlockPos(11, 2, 11));
        level.setBlockAndUpdate(inside, Blocks.SEA_LANTERN.defaultBlockState());
        level.setBlockAndUpdate(outside, Blocks.SEA_LANTERN.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_lamplighter", new BlockPos(3, 2, 3), false);
        helper.assertTrue(lua(companion, "area.new(\"lamp\")").succeeded()
                        && lua(companion, "area.add(\"lamp\", {box = " + luaBox(inside.offset(-1, -1, -1),
                                inside.offset(1, 1, 1)) + "})").succeeded(), "the lamp area could not be framed");
        ToolRun scanned = lua(companion, "scan.blocks(\"minecraft:sea_lantern\", {within = \"lamp\", radius = 16})");

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
