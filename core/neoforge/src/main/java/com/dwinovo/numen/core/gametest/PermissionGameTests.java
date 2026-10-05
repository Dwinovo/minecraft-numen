package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Listing;
import com.dwinovo.numen.task.TaskRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 权限层:主人的东西要问、分团挖掘、记住的同意与规则分层、右键与拿东西、原生通道上的退回。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class PermissionGameTests {

    /** 权限批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_permission")
    public static void preparePermissionBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 让一个<b>真玩家</b>(不是同伴)把一个方块物品放在 {@code rel} 上:走 {@code BlockItem.place} 的
     * 真实放置路径,放置记录的 mixin 就在那儿——测的是真机上会发生的那条链,不是手工写记录。
     */
    private static void playerPlaces(GameTestHelper helper, BlockPos rel, net.minecraft.world.item.Item item) {
        ServerLevel level = helper.getLevel();
        net.minecraft.world.level.block.Block block = ((net.minecraft.world.item.BlockItem) item).getBlock();
        // 裸的 ServerPlayer,不走登录(登录会给这个没有客户端的假人推同伴名册的载荷);
        // BlockItem.place 只要一个 ServerPlayer 身份,不要求它在玩家列表里。
        net.minecraft.server.level.ServerPlayer placer = new net.minecraft.server.level.ServerPlayer(
                level.getServer(), level,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "gametest_placer"),
                net.minecraft.server.level.ClientInformation.createDefault());
        BlockPos floor = helper.absolutePos(rel.below());
        var ctx = new net.minecraft.world.item.context.BlockPlaceContext(placer,
                net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(item),
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(floor),
                        net.minecraft.core.Direction.UP, floor, false));
        var result = ((net.minecraft.world.item.BlockItem) item).place(ctx);
        helper.assertTrue(result.consumesAction() && level.getBlockState(helper.absolutePos(rel)).is(block),
                "the mock player failed to place " + item + " at " + rel.toShortString());
        helper.assertTrue(com.dwinovo.numen.permission.PlacedBlocks.of(level)
                        .isPlaced(helper.absolutePos(rel), level.getBlockState(helper.absolutePos(rel))),
                "BlockItem.place by a real player was not recorded");
    }

    private static com.dwinovo.numen.permission.ConsentDesk desk(NumenPlayer companion) {
        return com.dwinovo.numen.permission.ConsentDesk.of(companion);
    }

    /** 屋子四面墙与脚下地板都记成玩家放的(一间房子的地板也是主人铺的)。 */
    private static void ownersRoom(GameTestHelper helper, int cx, int cz) {
        plankRoomAround(helper, cx, cz);
        ServerLevel level = helper.getLevel();
        var placed = com.dwinovo.numen.permission.PlacedBlocks.of(level);
        var owner = new com.dwinovo.numen.permission.PlacedBlocks.Placer(UUID.randomUUID(), "gametest_owner");
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                for (int y = 1; y <= 4; y++) {
                    BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
                    if (!level.getBlockState(pos).isAir()) {
                        placed.record(pos, owner);
                    }
                }
            }
        }
    }

    /** 主人的屋子西半边那一格:要出去得先走过屋里两格,再挖东墙。 */
    private static final BlockPos WEST_INSIDE = new BlockPos(6, 2, 7);

    /**
     * 把要问主人的格当墙({@code consent = false}):主人的屋子,许挖许放。没有路;回执说把要问的格算能走才有路、要改几格,给出旋钮的
     * 写法。照它不当墙再规划:计划列出那几格(asks)。墙一块不少,她还在屋里,没有弹过一张卡——规划不问主人,问只在走到那一格时。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void a_walk_that_walls_off_consent_cells_names_them_without_asking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ownersRoom(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_lodger", new BlockPos(7, 2, 7), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_landlord");
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(target) + ", {costs = {dig = true, place = true, consent = false}})");
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);
        ToolRun[] plan = new ToolRun[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(walk.done(), "the walk has not replied"))
                .thenExecute(() -> {
                    String reply = walk.receipt();
                    helper.assertTrue(!walk.ranToTheEnd() && "no_path".equals(walk.kind()),
                            "the walk through the owner's wall was accepted: " + reply);
                    helper.assertTrue(reply.contains("owner's consent") && reply.contains("costs = {consent = 10}"),
                            "the refusal does not say it needs the owner's consent and how to allow it: " + reply);
                    plan[0] = lua(companion, "local p = numen.route.plan({to = " + xyz(target) + ", costs = {dig = true, "
                            + "place = true}})\nreturn {ok = p.ok, asks = p.legs[1].asks}");
                })
                .thenWaitUntil(() -> helper.assertTrue(plan[0].done(), "route plan has not replied"))
                .thenExecute(() -> {
                    com.google.gson.JsonObject p = plan[0].data().getAsJsonObject("returned");
                    helper.assertTrue(p.get("ok").getAsBoolean() && p.getAsJsonArray("asks").size() > 0
                                    && p.getAsJsonArray("asks").toString().contains("oak_planks")
                                    && p.getAsJsonArray("asks").toString().contains("why"),
                            "the plan does not list the cells needing consent: " + plan[0].receipt());
                    helper.assertTrue(!asked[0], "a walled-off walk or a plan must not ask the owner");
                    helper.assertTrue(plankCount(helper, 7, 7) == planksBefore, "the owner's wall was damaged");
                    helper.assertTrue(companion.blockPosition().distSqr(target) > 3 * 3,
                            "companion got out through the owner's wall?!");
                    CompanionFactory.despawn(level.getServer(), companion);
                    leave(owner);
                })
                .thenSucceed();
    }

    /**
     * 越过边界才问:穿主人墙的那条路照常规划、照常开走——不在开走前问。她走过屋里那两格,走到墙前要挖它时才停下挂一条征询,
     * 这时墙一块不少;征询列出这一声答应放行的每一格,就是计划里要问的那几格(同一种木板、同一行规则),之后不再问;主人允许,
     * 她拆墙出去到达,回执说主人允许过。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void a_walk_asks_at_the_owners_wall_and_goes_on_when_allowed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ownersRoom(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_tenant", WEST_INSIDE, false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_host");
        BlockPos start = companion.blockPosition();
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun walk = lua(companion, "local p = numen.route.plan({to = " + xyz(target) + ", costs = {dig = true, "
                + "place = true}})\nprint(\"asks=\" .. #p.legs[1].asks)\nreturn numen.move.go(p)");
        boolean[] answered = new boolean[1];
        int[] listed = new int[1];
        int[] requests = new int[1];

        succeedWhen(helper, () -> {
            TaskRecord record = walk.task();
            if (!answered[0]) {
                helper.assertTrue(record != null, "the walk has not set off");
                var pending = desk(companion).pending();
                helper.assertTrue(pending != null, "no consent request at the wall");
                // 开走前不问:请求挂上的时候她已经走过了屋里那两格,站在墙前
                helper.assertTrue(companion.blockPosition().getX() - start.getX() >= 2,
                        "she asked before getting to the wall, at " + companion.blockPosition().toShortString());
                helper.assertTrue(record.getResult() == null, "the walk finished while waiting for the owner");
                helper.assertTrue(plankCount(helper, 7, 7) == planksBefore, "a plank broke before the owner said yes");
                helper.assertTrue(pending.items().stream().allMatch(i -> i.subject().equals("oak_planks")),
                        "the request does not name the wall: " + pending.items());
                listed[0] = pending.items().size();
                requests[0]++;
                answered[0] = desk(companion).answer(pending.id(),
                        com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
            }
            if (desk(companion).pending() != null) {
                // 墙的其余几格同一种木板,第一声答应已经放行,不该再问;真挂了就记下再答一次,让下面的断言说清
                requests[0]++;
                desk(companion).answer(desk(companion).pending().id(),
                        com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
            }
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "the walk has not finished");
            helper.assertTrue(record.getResult().success(), "the walk failed after the owner allowed it: " + reply);
            helper.assertTrue(walk.receipt() != null, "the program has not handed in its receipt");
            helper.assertTrue(walk.receipt().contains("asks=" + listed[0]) && listed[0] >= 2,
                    "the request did not list every cell of the plan the yes lets through (" + listed[0] + "): "
                            + walk.receipt());
            helper.assertTrue(requests[0] == 1, "the owner was asked " + requests[0] + " times");
            helper.assertTrue(plankCount(helper, 7, 7) < planksBefore, "no plank was broken");
            helper.assertTrue(reply.contains("the owner allowed"), "the reply does not say the owner allowed it: " + reply);
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 同一条路主人说不:走到墙前问,主人不答应,她就停在那里,以 denied 收场,理由是主人原话,下一步是绕开那一格再规划的那一行;
     * 墙一块不少,她没出屋。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void a_walk_refused_at_the_owners_wall_stops_there(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ownersRoom(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_squatter", WEST_INSIDE, false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_strict");
        BlockPos start = companion.blockPosition();
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(target) + ", {costs = {dig = true, place = true}})");
        boolean[] answered = new boolean[1];

        succeedWhen(helper, () -> {
            TaskRecord record = walk.task();
            if (!answered[0]) {
                var pending = desk(companion).pending();
                helper.assertTrue(pending != null, "no consent request at the wall");
                answered[0] = desk(companion).answer(pending.id(),
                        com.dwinovo.numen.permission.ConsentAnswer.Decision.DENY, "别拆我的墙");
            }
            String reply = record == null || record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "the walk has not finished");
            helper.assertTrue(!record.getResult().success() && "denied".equals(walk.kind())
                    && reply.contains("refused by the owner") && reply.contains("别拆我的墙"),
                    "the refusal does not quote the owner: " + reply);
            helper.assertTrue(walk.hint() != null && walk.hint().contains("numen.route.plan(") && walk.hint().contains("avoid"),
                    "the refusal does not give the plan around that cell: " + walk.hint());
            helper.assertTrue(plankCount(helper, 7, 7) == planksBefore, "the wall was damaged after a no");
            helper.assertTrue(companion.blockPosition().getX() - start.getX() >= 2
                            && companion.blockPosition().distSqr(target) > 3 * 3,
                    "she did not stop at the wall: " + companion.blockPosition().toShortString());
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 当墙就绕开:主人的一道木板墙横在半路,另一头留着口。要问的格当墙({@code consent = false})时她绕到口子过去,一张卡也不弹,
     * 墙一块不少。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void walling_off_consent_cells_goes_around_the_owners_wall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var placed = com.dwinovo.numen.permission.PlacedBlocks.of(level);
        var placer = new com.dwinovo.numen.permission.PlacedBlocks.Placer(UUID.randomUUID(), "gametest_owner");
        for (int z = 0; z <= 15; z++) {
            for (int y = 2; y <= 3; y++) {
                if (z != 14) {
                    BlockPos pos = helper.absolutePos(new BlockPos(8, y, z));
                    level.setBlockAndUpdate(pos, Blocks.OAK_PLANKS.defaultBlockState());
                    placed.record(pos, placer);
                }
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_polite", new BlockPos(2, 2, 2), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_gardener");
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 2));
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(target) + ", {costs = {dig = 1, place = true, consent = false}})");
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "the walk has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(target) <= 1,
                    "she did not get around the owner's wall: " + walk.outcome());
            helper.assertTrue(!asked[0], "a walk that walls off consent cells asked the owner");
            for (int z = 0; z <= 15; z++) {
                if (z != 14) {
                    helper.assertTrue(level.getBlockState(helper.absolutePos(new BlockPos(8, 2, z))).is(Blocks.OAK_PLANKS),
                            "the owner's wall lost a plank at z=" + z);
                }
            }
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * mine 挖到主人放的原木就问,同种原木只问一次:附近只有两根主人放的橡木,要两根。卡片挂上,
     * 主人允许,她把两根都挖了——第二根不再弹卡(同一行规则问出来的同一种方块本任务内已授权)。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void mine_asks_once_for_player_logs_then_mines(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> logs = List.of(new BlockPos(5, 2, 4), new BlockPos(5, 2, 6));
        for (BlockPos rel : logs) {
            playerPlaces(helper, rel, Items.OAK_LOG);
        }
        NumenPlayer companion = spawnAt(helper, "gametest_lumberjack", new BlockPos(2, 2, 5), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_forester");
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        Mining mine = mineScanned(helper, companion, 8, "minecraft:oak_log", 2);
        java.util.Set<Long> requests = new java.util.HashSet<>();
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            if (pending != null && requests.add(pending.id())) {
                desk(companion).answer(pending.id(), com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "mine has not finished");
            helper.assertTrue(mine.succeeded(), "mine failed after the owner allowed: " + mine.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.OAK_LOG) >= 2,
                    "companion has not gathered 2 logs: " + mine.outcome());
            helper.assertTrue(requests.size() == 1, "asked " + requests.size() + " times for the same kind of log");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /** 主人不让挖:mine 以 refused 收场,理由是主人原话,原木一根不少。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void mine_denied_quotes_the_owner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> logs = List.of(new BlockPos(5, 2, 4), new BlockPos(5, 2, 6));
        for (BlockPos rel : logs) {
            playerPlaces(helper, rel, Items.JUNGLE_LOG);
        }
        NumenPlayer companion = spawnAt(helper, "gametest_hewer", new BlockPos(2, 2, 5), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_keeper");
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        Mining mine = mineScanned(helper, companion, 8, "minecraft:jungle_log", 2);
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            if (pending != null) {
                desk(companion).answer(pending.id(), com.dwinovo.numen.permission.ConsentAnswer.Decision.DENY, "留着当柱子");
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "mine has not finished");
            helper.assertTrue(!mine.succeeded() && mine.outcome().contains("留着当柱子"),
                    "the refusal does not quote the owner: " + mine.outcome());
            for (BlockPos rel : logs) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.JUNGLE_LOG),
                        "a log was cut after the owner said no at " + rel.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 同一套定价自然排序:主人放的原木离她三格,野树九格。要两根——她走去砍野树,主人的原木一根不少,
     * 从头到尾没有弹过一张卡。没有剔除,主人的原木只是贵(需要同意的格乘十倍)。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void mine_prefers_wild_trees_by_price(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = List.of(new BlockPos(5, 2, 4), new BlockPos(5, 2, 6));
        List<BlockPos> wild = List.of(new BlockPos(11, 2, 4), new BlockPos(11, 2, 6));
        for (BlockPos rel : placed) {
            playerPlaces(helper, rel, Items.ACACIA_LOG);
        }
        for (BlockPos rel : wild) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.ACACIA_LOG.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_ranger", new BlockPos(2, 2, 5), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_warden");
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        Mining mine = mineScanned(helper, companion, 12, "minecraft:acacia_log", 2);
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "mine has not finished");
            helper.assertTrue(companion.getInventory().countItem(Items.ACACIA_LOG) >= 2,
                    "companion has not gathered 2 logs: " + mine.outcome());
            for (BlockPos rel : placed) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.ACACIA_LOG),
                        "a player-placed log was cut while wild ones stood nearby at " + rel.toShortString());
            }
            helper.assertTrue(!asked[0], "asked the owner although wild logs were there");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 出路全被主人的规则拦着:她关在一间白羊毛小屋里,主人写了 deny 行不许挖白羊毛,南瓜在屋外。回执说哪一格不许动、
     * 是哪一行规则,下一步是换个去处或问主人——和"要改地形"不是同一句。屋子与南瓜原样,也不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void mine_with_every_way_out_refused_says_which_cell_and_why(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> hut = boxCells(new BlockPos(3, 1, 3), 3, 4, 3, true);
        for (BlockPos rel : hut) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.WHITE_WOOL.defaultBlockState());
        }
        BlockPos pumpkin = helper.absolutePos(new BlockPos(10, 2, 10));
        level.setBlockAndUpdate(pumpkin, Blocks.PUMPKIN.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_walled_in", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_shepherd");
        storeOf(owner).add(com.dwinovo.numen.permission.Verdict.Kind.DENY,
                com.dwinovo.numen.permission.Rule.parse("break(minecraft:white_wool)"));
        Mining mine = mineScanned(helper, companion, 9, "minecraft:pumpkin", 1);
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "mine has not finished");
            String said = mine.outcome();
            helper.assertTrue(mine.task() == null, "a dig with every way out refused was accepted: " + mine.reply());
            helper.assertTrue(!mine.succeeded() && said.contains("had to stop: changing")
                            && said.contains("is refused") && said.contains("denied by rule")
                            && said.contains("ask your owner") && !said.contains("without changing terrain"),
                    "the reply does not say which cell is refused, by what, and what to do: " + said);
            helper.assertTrue(level.getBlockState(pumpkin).is(Blocks.PUMPKIN), "the pumpkin was mined");
            for (BlockPos rel : hut) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.WHITE_WOOL),
                        "the refused wool was broken at " + rel.toShortString());
            }
            helper.assertTrue(!asked[0], "a denied way out raised a consent card");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /** 挖这一团,挖完为止({@link GameTestKit#mine(NumenPlayer, String, int)}):团的方块原样写进程序。 */
    private static Mining mineCluster(NumenPlayer companion, com.google.gson.JsonObject cluster) {
        return mine(companion, blocksOf(cluster), 0);
    }

    /**
     * 只挖交给她的那一团:两棵分开的野树一扫成两团,交出其中一团。她挖完那三格就收场,另一团一格不少。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void mine_digs_only_the_given_cluster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> named = List.of(new BlockPos(4, 2, 4), new BlockPos(4, 3, 4), new BlockPos(4, 4, 4));
        List<BlockPos> other = List.of(new BlockPos(11, 2, 10), new BlockPos(11, 3, 10), new BlockPos(11, 4, 10));
        for (BlockPos rel : named) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.DARK_OAK_LOG.defaultBlockState());
        }
        for (BlockPos rel : other) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.DARK_OAK_LOG.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_feller", new BlockPos(7, 2, 7), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        ToolRun reply = scan(companion, 6, "minecraft:dark_oak_log");
        Mining[] mine = new Mining[1];

        succeedWhen(helper, () -> {
            if (mine[0] == null) {
                helper.assertTrue(reply.reply() != null, "scan_blocks has not replied");
                var clusters = clustersIn(reply.reply());
                var target = clusterHolding(clusters, helper.absolutePos(named.get(0)));
                var spared = clusterHolding(clusters, helper.absolutePos(other.get(0)));
                helper.assertTrue(target != null && spared != null && target != spared,
                        "the two trees are not two clusters: " + reply.reply());
                mine[0] = mineCluster(companion, target);
            }
            String result = mine[0].done() ? mine[0].outcome() : null;
            helper.assertTrue(result != null, "mine has not finished");
            helper.assertTrue(mine[0].succeeded() && mine[0].dug() == 3,
                    "mine did not dig the given cluster out: " + result);
            for (BlockPos rel : named) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).isAir(),
                        "a log of the given cluster is still standing at " + rel.toShortString());
            }
            for (BlockPos rel : other) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.DARK_OAK_LOG),
                        "a log outside the given cluster was cut at " + rel.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * "别碰这一格"由程序表达:两根野生诡异菌柄一扫成两团,程序把要留的那一格从扫到的方块里去掉,剩下的交给 {@code numen.work.dig}、
     * 要两个。她挖了另一根就收场(交给她的没有了),留下的那格原样立着。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void dig_leaves_standing_what_the_program_took_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos freeRel = new BlockPos(5, 2, 5);
        BlockPos keptRel = new BlockPos(9, 2, 5);
        level.setBlockAndUpdate(helper.absolutePos(freeRel), Blocks.WARPED_STEM.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(keptRel), Blocks.WARPED_STEM.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_forager", new BlockPos(7, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        BlockPos kept = helper.absolutePos(keptRel);
        ToolRun[] dig = new ToolRun[1];

        succeedWhen(helper, () -> {
            if (dig[0] == null) {
                dig[0] = lua(companion, """
                        local keep = %s
                        local rest = {}
                        for _, c in ipairs(numen.scan.blocks("minecraft:warped_stem", {radius = 5})) do
                          for _, b in ipairs(c.blocks) do
                            if b.pos.x ~= keep.x or b.pos.y ~= keep.y or b.pos.z ~= keep.z then
                              rest[#rest + 1] = b
                            end
                          end
                        end
                        return numen.work.dig(rest, {count = 2})
                        """.formatted(xyz(kept)));
            }
            helper.assertTrue(dig[0].done(), "dig has not finished");
            String reply = dig[0].outcome();
            helper.assertTrue(level.getBlockState(helper.absolutePos(freeRel)).isAir(), "the free stem was not dug");
            helper.assertTrue(level.getBlockState(kept).is(Blocks.WARPED_STEM), "the cell the program took out was dug");
            helper.assertTrue(dig[0].succeeded() && reply.contains("dug 1 cell(s) of warped_stem.")
                            && !reply.contains("out of my reach"),
                    "the reply does not say what was left was dug out: " + reply);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 交给她的那一团被拒就停:主人写了 deny 行不许挖绯红菌柄;扫描只说找到了什么,不说主人许不许。mine 那一团,派发当场按拒绝收场、
     * 理由是那一行规则,菌柄一根不少,也不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void mine_refused_stops_with_the_reason(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> stems = List.of(new BlockPos(6, 2, 6), new BlockPos(6, 3, 6));
        for (BlockPos rel : stems) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.CRIMSON_STEM.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_objector", new BlockPos(3, 2, 6), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_botanist");
        storeOf(owner).add(com.dwinovo.numen.permission.Verdict.Kind.DENY,
                com.dwinovo.numen.permission.Rule.parse("break(minecraft:crimson_stem)"));
        ToolRun reply = scan(companion, 5, "minecraft:crimson_stem");
        Mining[] mine = new Mining[1];
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            if (mine[0] == null) {
                helper.assertTrue(reply.reply() != null, "scan_blocks has not replied");
                var cluster = clusterHolding(clustersIn(reply.reply()), helper.absolutePos(stems.get(0)));
                helper.assertTrue(cluster != null && !cluster.has("permission") && !reply.reply().contains("denied"),
                        "the scan tells her what her owner allows: " + reply.reply());
                mine[0] = mineCluster(companion, cluster);
            }
            String result = mine[0].done() ? mine[0].outcome() : null;
            helper.assertTrue(result != null, "mine has not finished");
            helper.assertTrue(mine[0].task() == null, "a dig of a denied cluster was accepted: " + mine[0].reply());
            helper.assertTrue(!mine[0].succeeded() && result.contains("denied by rule"),
                    "the refusal does not carry the rule: " + result);
            for (BlockPos rel : stems) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.CRIMSON_STEM),
                        "a denied stem was cut at " + rel.toShortString());
            }
            helper.assertTrue(!asked[0], "a denied cluster raised a consent card");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 她一路往下挖掉的点名格都算她挖的:四根原木叠成一柱,四面黑曜石围成竖井,她站在柱顶。交出这一团,脚下那一根挖掉
     * 她就落下去、接着挖下一根,为看见下面那根挖开的遮挡本身也是点名格。四格都记成她挖的,没有一格记成"别人动过"。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void mine_counts_cells_she_broke_on_the_way(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> column = List.of(new BlockPos(7, 2, 7), new BlockPos(7, 3, 7), new BlockPos(7, 4, 7),
                new BlockPos(7, 5, 7));
        for (int y = 2; y <= 9; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(7 + dx, y, 7 + dz)),
                                Blocks.OBSIDIAN.defaultBlockState());
                    }
                }
            }
        }
        for (BlockPos rel : column) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
        }
        // 原版 GameTest 给测试结构封了一层屏障顶,正好压在竖井里她头顶那一格:竖井上半截明确清空,她才站得直
        for (int y = 6; y <= 9; y++) {
            level.setBlockAndUpdate(helper.absolutePos(new BlockPos(7, y, 7)), Blocks.AIR.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_sinker", new BlockPos(7, 6, 7), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        ToolRun reply = scan(companion, 6, "minecraft:stripped_spruce_log");
        Mining[] mine = new Mining[1];

        succeedWhen(helper, () -> {
            if (mine[0] == null) {
                helper.assertTrue(reply.reply() != null, "scan_blocks has not replied");
                var cluster = clusterHolding(clustersIn(reply.reply()), helper.absolutePos(column.get(0)));
                helper.assertTrue(cluster != null && cluster.get("count").getAsInt() == 4,
                        "the column is not one cluster of four: " + reply.reply());
                mine[0] = mineCluster(companion, cluster);
            }
            String result = mine[0].done() ? mine[0].outcome() : null;
            helper.assertTrue(result != null, "mine has not finished");
            for (BlockPos rel : column) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).isAir(),
                        "a log of the column is still standing at " + rel.toShortString());
            }
            helper.assertTrue(mine[0].succeeded() && mine[0].dug() == 4
                    && !result.contains("gone"), "the cells she broke on the way were not counted as hers: " + result);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * numen.use.hit 左键打主人的箱子:动手之前挂一条征询,这次调用悬着;主人允许后箱子没了。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void interact_left_click_on_owners_chest_asks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chestRel = new BlockPos(6, 2, 5);
        playerPlaces(helper, chestRel, Items.CHEST);
        BlockPos chest = helper.absolutePos(chestRel);
        NumenPlayer companion = spawnAt(helper, "gametest_poker", new BlockPos(4, 2, 5), true);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_hoarder");
        ToolRun[] dig = new ToolRun[1];
        boolean[] answered = new boolean[1];
        helper.runAfterDelay(5, () -> {
            dig[0] = lua(companion, "numen.use.hit(" + xyz(chest) + ")");
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(dig[0] != null, "use block not dispatched yet");
            if (!answered[0]) {
                var pending = desk(companion).pending();
                helper.assertTrue(pending != null, "no consent request before hitting the owner's chest");
                helper.assertTrue(!dig[0].done(), "the call did not wait for the owner");
                helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST), "the chest broke before the owner said yes");
                answered[0] = desk(companion).answer(pending.id(),
                        com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
            }
            helper.assertTrue(dig[0].done(), "use hit has not finished");
            helper.assertTrue(level.getBlockState(chest).isAir(),
                    "the chest is still there after the owner allowed: " + dig[0].outcome());
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 等主人点头的时候那只动物挪了窝,征询还是原来那一张:点名打一头起了名字的猪,征询挂上后每隔几刻把它挪一格,
     * 号始终不变——实体认的是那一只,不是它脚下的格,挪一步不是新的请求。主人拒绝后猪还活着。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void a_target_that_moves_keeps_its_consent_request(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(4, 2, 4));
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_swineherd");
        var pig = EntityType.PIG.create(level);
        helper.assertTrue(pig != null, "pig did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(6, 2, 4));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        pig.setCustomName(net.minecraft.network.chat.Component.literal("Wilbur"));
        level.addFreshEntity(pig);
        TaskRecord record = lua(companion, "numen.fight.attack(" + pig.getId() + ")").task();
        long[] asked = {0L};
        int[] waited = {0};
        boolean[] denied = {false};
        helper.onEachTick(() -> {
            if (denied[0]) {
                return;
            }
            var pending = desk(companion).pending();
            if (asked[0] == 0L) {
                if (pending != null) {
                    asked[0] = pending.id();
                }
                return;
            }
            helper.assertTrue(pending != null && pending.id() == asked[0],
                    "the pig moved and the request was raised again: " + pending);
            waited[0]++;
            if (waited[0] % 5 == 0) {
                pig.teleportTo(pig.getX() + (waited[0] % 10 == 0 ? -1 : 1), pig.getY(), pig.getZ());
            }
            if (waited[0] >= 40) {
                denied[0] = desk(companion).answer(asked[0],
                        com.dwinovo.numen.permission.ConsentAnswer.Decision.DENY, "");
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(denied[0] && record.getResult() != null, "attack has not finished after the no");
            helper.assertTrue(pig.isAlive(), "the pig was hit after the owner said no");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /** numen.inv.drop 每次问:调用悬着等主人;允许后东西丢出来。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void drop_items_waits_for_the_owner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_giver", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_receiver");
        companion.getInventory().add(new ItemStack(Items.DIAMOND, 3));
        ToolRun record = lua(companion, "numen.inv.drop(\"minecraft:diamond\", {count = 3})");
        boolean[] answered = new boolean[1];

        succeedWhen(helper, () -> {
            if (!answered[0]) {
                var pending = desk(companion).pending();
                helper.assertTrue(pending != null, "inv drop did not ask");
                helper.assertTrue(!record.done(), "inv drop finished without an answer");
                helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 3, "dropped before the answer");
                answered[0] = desk(companion).answer(pending.id(),
                        com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
            }
            String reply = record.done() ? record.outcome() : null;
            helper.assertTrue(reply != null && record.succeeded(), "inv drop did not finish: " + reply);
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 0, "nothing was dropped");
            helper.assertTrue(reply.contains("the owner allowed"), "the reply does not say the owner allowed: " + reply);
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 卡片挂着时主人按停止:numen.inv.drop 悬着等答复,主人没点卡片而是按了停止(与 CancelTasksPayload 同一个入口)。
     * 这件活按主人停止收场、消息写明是主人停的,挂着的征询随之撤掉,东西一件没丢。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void an_owner_stop_while_a_card_is_up_withdraws_the_card(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_hesitant", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_changed_mind");
        companion.getInventory().add(new ItemStack(Items.GOLD_INGOT, 4));
        ToolRun record = lua(companion, "numen.inv.drop(\"minecraft:gold_ingot\", {count = 4})");
        boolean[] stopped = new boolean[1];

        succeedWhen(helper, () -> {
            if (!stopped[0]) {
                helper.assertTrue(desk(companion).pending() != null, "inv drop did not ask");
                com.dwinovo.numen.task.CompanionTickDispatcher.cancelFor(companion);
                stopped[0] = true;
            }
            String reply = record.done() ? record.outcome() : null;
            helper.assertTrue(reply != null, "the stopped drop has not settled");
            helper.assertTrue(!record.succeeded() && reply.startsWith("the owner pressed Stop"),
                    "the result does not say the owner stopped it: " + reply);
            helper.assertTrue(desk(companion).pending() == null, "the card is still up after the stop");
            helper.assertTrue(companion.getInventory().countItem(Items.GOLD_INGOT) == 4, "dropped after the stop");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /** 没人答复:到点按拒绝,理由是"主人不在场,无法征得同意";东西还在身上。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void unanswered_consent_times_out_as_denied(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_waiter", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_absent");
        companion.getInventory().add(new ItemStack(Items.EMERALD, 2));
        ToolRun record = lua(companion, "numen.inv.drop(\"minecraft:emerald\", {count = 2})");

        succeedWhen(helper, () -> {
            String reply = record.done() ? record.outcome() : null;
            helper.assertTrue(reply != null, "still waiting for the owner");
            helper.assertTrue(!record.succeeded()
                    && reply.contains(com.dwinovo.numen.permission.ConsentDesk.OWNER_ABSENT),
                    "a timeout must refuse as the owner being absent: " + reply);
            helper.assertTrue(companion.getInventory().countItem(Items.EMERALD) == 2, "dropped without consent");
            helper.assertTrue(desk(companion).pending() == null, "the card is still up");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /** observe 模式拒绝一切改动:自然原木也不砍,任务以 refused 收场,原木一根不少,也不弹卡。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void observe_mode_refuses_every_change(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> logs = List.of(new BlockPos(8, 2, 6), new BlockPos(8, 2, 8));
        for (BlockPos rel : logs) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.BIRCH_LOG.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_watcher", new BlockPos(6, 2, 7), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_viewer");
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        // 先扫好再切 observe:这条量的是挖
        ToolRun scanned = scan(companion, 8, "minecraft:birch_log");
        ToolRun[] mine = new ToolRun[1];
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            if (mine[0] == null) {
                helper.assertTrue(scanned.succeeded(), "the scan has not found the logs: " + scanned.reply());
                com.dwinovo.numen.permission.Permission.setMode(companion,
                        com.dwinovo.numen.permission.Mode.OBSERVE);
                mine[0] = lua(companion, "numen.work.dig(" + blocksIn(scanned.reply()) + ", {count = " + 2 + "})");
            }
            helper.assertTrue(mine[0].done(), "mine has not finished");
            helper.assertTrue(!mine[0].succeeded() && mine[0].outcome().contains("observe mode"),
                    "observe mode must refuse with its reason: " + mine[0].outcome());
            helper.assertTrue(!asked[0], "observe mode asked the owner");
            for (BlockPos rel : logs) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.BIRCH_LOG),
                        "observe mode cut a log at " + rel.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /** bypass 模式全放行:玩家放的原木照砍,不弹卡。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void bypass_mode_allows_everything(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = List.of(new BlockPos(8, 2, 6), new BlockPos(8, 2, 8));
        for (BlockPos rel : placed) {
            playerPlaces(helper, rel, Items.SPRUCE_LOG);
        }
        NumenPlayer companion = spawnAt(helper, "gametest_trusted", new BlockPos(3, 2, 7), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_trusting");
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        com.dwinovo.numen.permission.Permission.setMode(companion, com.dwinovo.numen.permission.Mode.BYPASS);
        mineScanned(helper, companion, 8, "minecraft:spruce_log", 2);
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            helper.assertTrue(companion.getInventory().countItem(Items.SPRUCE_LOG) >= 2,
                    "bypass mode did not let her cut the player-placed logs");
            for (BlockPos rel : placed) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).isAir(),
                        "a log is still standing at " + rel.toShortString());
            }
            helper.assertTrue(!asked[0], "bypass mode asked the owner");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /** 以玩家 {@code who} 的身份跑一条命令,和在聊天栏里敲的一样;回话(成功与失败的)收进返回的列表。 */
    private static List<String> runAs(ServerPlayer who, String command) {
        List<String> said = new ArrayList<>();
        net.minecraft.commands.CommandSource capture = new net.minecraft.commands.CommandSource() {
            @Override
            public void sendSystemMessage(net.minecraft.network.chat.Component message) {
                said.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        who.getServer().getCommands().performPrefixedCommand(who.createCommandSourceStack().withSource(capture),
                command);
        return said;
    }

    private static com.dwinovo.numen.permission.PermissionStore storeOf(ServerPlayer owner) {
        return com.dwinovo.numen.permission.PermissionStore.of(owner.getServer(), owner.getUUID());
    }

    /**
     * 允许并记住:挖主人放的第一块圆石问一次,主人选"允许并记住",他的 allow 表多了
     * {@code break(placed & minecraft:cobblestone)};第二块圆石另起一次调用,不再问、直接挖掉;主人放的橡木板没被
     * 记住,照旧问。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void remembered_consent_stops_asking_for_that_kind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos firstRel = new BlockPos(6, 2, 4);
        BlockPos secondRel = new BlockPos(6, 2, 6);
        BlockPos plankRel = new BlockPos(6, 2, 7);
        playerPlaces(helper, firstRel, Items.COBBLESTONE);
        playerPlaces(helper, secondRel, Items.COBBLESTONE);
        playerPlaces(helper, plankRel, Items.OAK_PLANKS);
        NumenPlayer companion = spawnAt(helper, "gametest_mason", new BlockPos(4, 2, 5), true);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_quarry");
        ToolRun[] calls = new ToolRun[3];
        int[] step = {0};
        helper.runAfterDelay(5, () -> {
            calls[0] = click(helper, companion, "left", firstRel);
            step[0] = 1;
        });
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            switch (step[0]) {
                case 1 -> {
                    if (pending != null) {
                        helper.assertTrue(pending.items().get(0).rule().equals("break(placed)"),
                                "the first cobblestone was asked under another rule: " + pending.items());
                        desk(companion).answer(pending.id(),
                                com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_REMEMBER, "");
                    }
                    if (calls[0].done()) {
                        helper.assertTrue(calls[0].succeeded(),
                                "the first dig failed after allow-and-remember: " + calls[0].outcome());
                        calls[1] = click(helper, companion, "left", secondRel);
                        step[0] = 2;
                    }
                }
                case 2 -> {
                    helper.assertTrue(pending == null, "asked again for a remembered kind: " + pending);
                    if (calls[1].done()) {
                        helper.assertTrue(calls[1].succeeded(),
                                "the second cobblestone was not dug: " + calls[1].outcome());
                        calls[2] = click(helper, companion, "left", plankRel);
                        step[0] = 3;
                    }
                }
                case 3 -> {
                    if (pending != null) {
                        helper.assertTrue(pending.items().get(0).subject().equals("oak_planks")
                                        && pending.items().get(0).rule().equals("break(placed)"),
                                "the planks were asked under another rule: " + pending.items());
                        desk(companion).answer(pending.id(),
                                com.dwinovo.numen.permission.ConsentAnswer.Decision.DENY, "木板留着");
                        step[0] = 4;
                    }
                }
                default -> { }
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(step[0] == 4 && calls[2].done(), "the three digs have not finished");
            helper.assertTrue(level.getBlockState(helper.absolutePos(firstRel)).isAir()
                    && level.getBlockState(helper.absolutePos(secondRel)).isAir(), "a cobblestone is still there");
            helper.assertTrue(level.getBlockState(helper.absolutePos(plankRel)).is(Blocks.OAK_PLANKS),
                    "the planks were dug after the owner said no");
            helper.assertTrue(!calls[1].outcome().contains("the owner allowed"),
                    "the second dig went through a consent: " + calls[1].outcome());
            List<String> allow = storeOf(owner).rules().allow().stream().map(Object::toString).toList();
            helper.assertTrue(allow.equals(List.of("break(placed & minecraft:cobblestone)")),
                    "the owner's allow table is not the remembered row: " + allow);
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 主人手写的 ask 行压过出厂 allow 行:主人用命令写下 {@code ask break(!placed & !block_entity)},她去挖一块
     * 自然石头也要问;写错的规则回教学式的错误、一行不进表。主人不让,石头一块不少。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void an_owners_ask_row_beats_the_factory_allow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stoneRel = new BlockPos(6, 2, 5);
        level.setBlockAndUpdate(helper.absolutePos(stoneRel), Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_sculptor", new BlockPos(4, 2, 5), true);
        ServerPlayer owner = presentPlayer(helper, companion, "gametest_geologist");

        List<String> mistake = runAs(owner, "numen permission rules add ask brek(placed)");
        helper.assertTrue(mistake.stream().anyMatch(m -> m.contains("unknown verb 'brek'") && m.contains("break")),
                "a mistyped rule was not taught: " + mistake);
        List<String> added = runAs(owner, "numen permission rules add ask break(!placed & !block_entity)");
        helper.assertTrue(added.stream().anyMatch(m -> m.contains("Added to ask")), "the row was not added: " + added);
        helper.assertTrue(storeOf(owner).rules().ask().size() == 1, "the owner's ask table: " + storeOf(owner).rules());
        List<String> listed = runAs(owner, "numen permission rules list");
        helper.assertTrue(listed.stream().anyMatch(m -> m.contains("1. break(!placed & !block_entity)")
                && m.contains("Factory rules")), "the list does not show both layers: " + listed);

        ToolRun[] call = new ToolRun[1];
        boolean[] answered = new boolean[1];
        helper.runAfterDelay(5, () -> call[0] = click(helper, companion, "left", stoneRel));
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            if (pending != null && !answered[0]) {
                helper.assertTrue(pending.items().get(0).rule().equals("break(!placed & !block_entity)"),
                        "asked under another rule: " + pending.items());
                answered[0] = desk(companion).answer(pending.id(),
                        com.dwinovo.numen.permission.ConsentAnswer.Decision.DENY, "石头别动");
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(call[0] != null && call[0].done(), "use block has not finished");
            helper.assertTrue(answered[0], "the owner's ask row did not raise a card");
            helper.assertTrue(!call[0].succeeded() && call[0].outcome().contains("石头别动"),
                    "the refusal does not quote the owner: " + call[0].outcome());
            helper.assertTrue(level.getBlockState(helper.absolutePos(stoneRel)).is(Blocks.STONE), "the stone was dug");
            List<String> removed = runAs(owner, "numen permission rules remove ask 1");
            helper.assertTrue(removed.stream().anyMatch(m -> m.contains("Removed from ask"))
                    && storeOf(owner).rules().ask().isEmpty(), "remove did not take the row out: " + removed);
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * {@code /numen consent} 与卡片是同一个入口:第一次丢钻石由卡片的网络载荷答复,第二次丢绿宝石由主人敲命令
     * "允许并记住"答复——两次都丢了、回执都交代主人允许了(记住的那次还交代记下了哪一行);记住之后第三次丢绿宝石
     * 不再问。带附言的允许不收(附言只随拒绝);别人敲命令答不了;
     * 答一个没挂着的号说清楚没有。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void consent_command_answers_like_the_card(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_almoner", new BlockPos(4, 2, 4), false);
        ServerPlayer owner = presentPlayer(helper, companion, "gametest_beggar");
        ServerPlayer stranger = presentPlayer(helper, null, "gametest_passerby");
        companion.getInventory().add(new ItemStack(Items.DIAMOND, 2));
        companion.getInventory().add(new ItemStack(Items.EMERALD, 4));
        ToolRun[] calls = new ToolRun[3];
        int[] step = {0};
        calls[0] = lua(companion, "numen.inv.drop(\"minecraft:diamond\", {count = 1})");
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            switch (step[0]) {
                case 0 -> {
                    if (pending != null) {
                        List<String> said = runAs(stranger, "numen consent allow " + pending.id());
                        helper.assertTrue(said.stream().anyMatch(m -> m.contains("not yours")),
                                "a stranger's answer was not turned away: " + said);
                        helper.assertTrue(desk(companion).pending() == pending, "a stranger's command settled it");
                        com.dwinovo.numen.network.payload.ConsentReplyPayload.handle(
                                new com.dwinovo.numen.network.payload.ConsentReplyPayload(companion.getUUID(),
                                        pending.id(), com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE,
                                        "小心点"), owner);
                        helper.assertTrue(desk(companion).pending() == pending,
                                "an allow carrying a note was taken; a note only goes with a deny");
                        com.dwinovo.numen.network.payload.ConsentReplyPayload.handle(
                                new com.dwinovo.numen.network.payload.ConsentReplyPayload(companion.getUUID(),
                                        pending.id(), com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE,
                                        ""), owner);
                        step[0] = 1;
                    }
                }
                case 1 -> {
                    if (calls[0].done()) {
                        helper.assertTrue(calls[0].succeeded()
                                        && calls[0].outcome().contains("the owner allowed"),
                                "the card answer did not go through: " + calls[0].outcome());
                        calls[1] = lua(companion, "numen.inv.drop(\"minecraft:emerald\", {count = 2})");
                        step[0] = 2;
                    }
                }
                case 2 -> {
                    if (pending != null) {
                        List<String> said = runAs(owner, "numen consent remember " + pending.id());
                        helper.assertTrue(said.stream().anyMatch(m -> m.contains("Answered consent request")),
                                "the owner's command was not taken: " + said);
                        step[0] = 3;
                    }
                }
                case 3 -> {
                    if (calls[1].done()) {
                        helper.assertTrue(calls[1].succeeded()
                                        && calls[1].outcome().contains("remembered it"),
                                "the command answer did not go through: " + calls[1].outcome());
                        calls[2] = lua(companion, "numen.inv.drop(\"minecraft:emerald\", {count = 2})");
                        step[0] = 4;
                    }
                }
                case 4 -> {
                    helper.assertTrue(pending == null, "asked again after remembering: " + pending);
                    if (calls[2].done()) {
                        step[0] = 5;
                    }
                }
                default -> { }
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(step[0] == 5, "the three drops have not finished");
            helper.assertTrue(calls[2].succeeded(), "the remembered drop failed: " + calls[2].outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 1
                    && companion.getInventory().countItem(Items.EMERALD) == 0, "the drops did not all happen");
            helper.assertTrue(storeOf(owner).rules().allow().stream().map(Object::toString).toList()
                    .equals(List.of("drop(minecraft:emerald)")), "the remembered row: " + storeOf(owner).rules().allow());
            List<String> stale = runAs(owner, "numen consent deny 987654321");
            helper.assertTrue(stale.stream().anyMatch(m -> m.contains("No pending consent request")),
                    "answering a missing request did not say so: " + stale);
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
            leave(stranger);
        });
    }

    /**
     * 放的人照实记:同伴 A 放下一块木板,记在 A 名下;A 自己拆由出厂的 {@code break(self_placed & !contents)} 放行
     * (她垫的、搭的是她的),同伴 B 要拆就得问——在 B 看来那是别人放的({@code break(placed)})。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_permission")
    public static void her_own_blocks_are_hers_but_another_companion_asks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer bridger = spawnAt(helper, "gametest_bridger", new BlockPos(3, 2, 3), false);
        NumenPlayer neighbour = spawnAt(helper, "gametest_neighbour", new BlockPos(11, 2, 11), false);
        BlockPos rel = new BlockPos(6, 2, 6);
        BlockPos floor = helper.absolutePos(rel.below());
        var ctx = new net.minecraft.world.item.context.BlockPlaceContext(bridger,
                net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_PLANKS),
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(floor),
                        net.minecraft.core.Direction.UP, floor, false));
        var result = ((net.minecraft.world.item.BlockItem) Items.OAK_PLANKS).place(ctx);
        BlockPos pos = helper.absolutePos(rel);
        helper.assertTrue(result.consumesAction() && level.getBlockState(pos).is(Blocks.OAK_PLANKS),
                "the companion failed to place the plank");

        var placer = com.dwinovo.numen.permission.PlacedBlocks.of(level).placerAt(pos, level.getBlockState(pos));
        helper.assertTrue(placer != null && placer.id().equals(bridger.getUUID()),
                "her plank was not recorded as hers: " + placer);
        var dig = com.dwinovo.numen.permission.Action.breakBlock(pos, level.getBlockState(pos));
        helper.assertTrue(com.dwinovo.numen.permission.Permission.gateFor(bridger).judgeLive(dig, level).allowed(),
                "she has to ask to take back her own plank");
        helper.assertTrue(com.dwinovo.numen.permission.Permission.gateFor(neighbour).judgeLive(dig, level).asks(),
                "another companion would break her plank without asking");
        CompanionFactory.despawn(level.getServer(), bridger);
        CompanionFactory.despawn(level.getServer(), neighbour);
        helper.succeed();
    }

    /**
     * 记住的规则按活世界推:空箱子问的是 {@code break(block_entity)},记下的是
     * {@code break(block_entity & minecraft:chest & !contents)};装着东西的问的是撤不回的那一行,记下的带着
     * {@code contents};有名字的狼问的是 {@code attack(named)},记下的只认这一只。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void remembering_derives_the_scope_from_the_live_world(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = helper.absolutePos(new BlockPos(6, 2, 5));
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        var wolf = EntityType.WOLF.create(level);
        helper.assertTrue(wolf != null, "wolf did not spawn");
        BlockPos wolfAt = helper.absolutePos(new BlockPos(6, 2, 9));
        wolf.moveTo(wolfAt.getX() + 0.5, wolfAt.getY(), wolfAt.getZ() + 0.5, 0.0f, 0.0f);
        wolf.setNoAi(true);
        wolf.setCustomName(net.minecraft.network.chat.Component.literal("Rex"));
        level.addFreshEntity(wolf);
        NumenPlayer companion = spawnAt(helper, "gametest_scribe", new BlockPos(3, 2, 5), false);

        var gate = com.dwinovo.numen.permission.Permission.gateFor(companion);
        var digEmpty = com.dwinovo.numen.permission.Action.breakBlock(chest, level.getBlockState(chest));
        var emptyVerdict = gate.judgeLive(digEmpty, level);
        helper.assertTrue(emptyVerdict.asks() && emptyVerdict.rule().toString().equals("break(block_entity)"),
                "an empty chest: " + emptyVerdict);
        String emptyRow = gate.consentItemLive(digEmpty, emptyVerdict, level).remember().toString();
        helper.assertTrue(emptyRow.equals("break(block_entity & minecraft:chest & !contents)"), "remembered " + emptyRow);

        var attack = com.dwinovo.numen.permission.Action.attack(wolf);
        var wolfVerdict = gate.judgeLive(attack, level);
        helper.assertTrue(wolfVerdict.asks() && wolfVerdict.rule().toString().equals("attack(named)"),
                "a named wolf: " + wolfVerdict);
        String wolfRow = gate.consentItemLive(attack, wolfVerdict, level).remember().toString();
        helper.assertTrue(wolfRow.equals("attack(entity:" + wolf.getUUID() + ")"), "remembered " + wolfRow);

        ((net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chest))
                .setItem(0, new ItemStack(Items.DIAMOND));
        var digFull = com.dwinovo.numen.permission.Action.breakBlock(chest, level.getBlockState(chest));
        var fullVerdict = gate.judgeLive(digFull, level);
        String fullRow = gate.consentItemLive(digFull, fullVerdict, level).remember().toString();
        helper.assertTrue(fullRow.equals("break(block_entity & contents & minecraft:chest)"), "remembered " + fullRow);

        wolf.discard();
        CompanionFactory.despawn(level.getServer(), companion);
        helper.succeed();
    }

    /** 把打开的界面里第 0 格整叠拿进背包。 */
    private static ToolRun takeFirstSlot(NumenPlayer companion) {
        return lua(companion, "numen.gui.quick(0)");
    }

    /**
     * observe 模式只看不动:主人用命令切到 observe,右键开箱子被拒、界面没开;切回 ask 开了箱子,再切到 observe,
     * 从箱子里拿东西被拒,钻石还在箱子里。从头到尾不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void observe_mode_refuses_opening_and_taking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chestRel = new BlockPos(6, 2, 5);
        BlockPos chest = chestWithDiamonds(helper, chestRel, 5);
        NumenPlayer companion = spawnAt(helper, "gametest_peeker", new BlockPos(4, 2, 5), false);
        ServerPlayer owner = presentPlayer(helper, companion, "gametest_curator");
        ToolRun[] calls = new ToolRun[3];
        int[] step = {0};
        boolean[] asked = new boolean[1];
        helper.runAfterDelay(5, () -> {
            List<String> said = runAs(owner, "numen permission mode gametest_peeker observe");
            helper.assertTrue(com.dwinovo.numen.permission.Permission.modeOf(companion)
                    == com.dwinovo.numen.permission.Mode.OBSERVE, "the mode command did not set observe: " + said);
            calls[0] = click(helper, companion, "right", chestRel);
            step[0] = 1;
        });
        helper.onEachTick(() -> {
            asked[0] |= desk(companion).pending() != null;
            switch (step[0]) {
                case 1 -> {
                    if (calls[0].done()) {
                        helper.assertTrue(!calls[0].succeeded()
                                        && calls[0].outcome().contains("observe mode"),
                                "observe mode let her open the chest: " + calls[0].outcome());
                        helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "a GUI opened anyway");
                        runAs(owner, "numen permission mode gametest_peeker ask");
                        calls[1] = click(helper, companion, "right", chestRel);
                        step[0] = 2;
                    }
                }
                case 2 -> {
                    if (calls[1].done()) {
                        helper.assertTrue(calls[1].succeeded()
                                        && companion.containerMenu != companion.inventoryMenu,
                                "the chest did not open in ask mode: " + calls[1].outcome());
                        runAs(owner, "numen permission mode gametest_peeker observe");
                        calls[2] = takeFirstSlot(companion);
                        step[0] = 3;
                    }
                }
                case 3 -> {
                    if (calls[2].done()) {
                        step[0] = 4;
                    }
                }
                default -> { }
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(step[0] == 4, "the calls have not finished");
            helper.assertTrue(!calls[2].succeeded() && calls[2].outcome().contains("observe mode"),
                    "observe mode let her take: " + calls[2].outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 0, "she took a diamond");
            var box = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chest);
            helper.assertTrue(box.getItem(0).is(Items.DIAMOND) && box.getItem(0).getCount() == 5,
                    "the chest lost its diamonds");
            helper.assertTrue(!asked[0], "observe mode asked the owner");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 主人写 {@code ask take(*)} 之后,开箱子照出厂规则放行,拿东西要问:transfer 悬着、钻石还在箱子里;主人允许后
     * 钻石进了她的背包。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void an_owners_ask_take_row_asks_before_taking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chestRel = new BlockPos(6, 2, 5);
        BlockPos chest = chestWithDiamonds(helper, chestRel, 3);
        NumenPlayer companion = spawnAt(helper, "gametest_borrower", new BlockPos(4, 2, 5), false);
        ServerPlayer owner = presentPlayer(helper, companion, "gametest_lender");
        runAs(owner, "numen permission rules add ask take(*)");
        ToolRun[] calls = new ToolRun[2];
        int[] step = {0};
        helper.runAfterDelay(5, () -> {
            calls[0] = click(helper, companion, "right", chestRel);
            step[0] = 1;
        });
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            switch (step[0]) {
                case 1 -> {
                    helper.assertTrue(pending == null, "opening the chest asked: " + pending);
                    if (calls[0].done()) {
                        helper.assertTrue(calls[0].succeeded()
                                        && companion.containerMenu != companion.inventoryMenu,
                                "the chest did not open: " + calls[0].outcome());
                        calls[1] = takeFirstSlot(companion);
                        step[0] = 2;
                    }
                }
                case 2 -> {
                    if (pending != null) {
                        helper.assertTrue(pending.items().get(0).rule().equals("take(*)")
                                        && pending.items().get(0).subject().equals("chest"),
                                "asked something else: " + pending.items());
                        helper.assertTrue(!calls[1].done(), "transfer did not wait for the owner");
                        helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 0,
                                "took before the owner answered");
                        desk(companion).answer(pending.id(),
                                com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
                        step[0] = 3;
                    }
                }
                default -> { }
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(step[0] == 3 && calls[1].done(), "transfer has not finished");
            helper.assertTrue(calls[1].succeeded(), "transfer failed after allow: "
                    + calls[1].outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 3, "the diamonds did not arrive");
            var box = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chest);
            helper.assertTrue(box.getItem(0).isEmpty(), "the chest still holds the diamonds");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 模拟一个在原生通道里取消破坏事件的模组:把登记在这里的身体的破坏事件全部取消。监听器第一次用到时
     * 挂一次。
     */
    private static final class BreakVeto {
        static final java.util.Set<UUID> LOCKED = java.util.concurrent.ConcurrentHashMap.newKeySet();

        static {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e) -> {
                        if (e.getPlayer() != null && LOCKED.contains(e.getPlayer().getUUID())) {
                            e.setCanceled(true);
                        }
                    });
        }
    }

    /**
     * 别的模组在原生通道里取消了破坏事件:权限层放行了(自然泥土),挖掘落点照真客户端挖下去,服务端退回来——
     * 以 refused 收场,理由写明服务器没让挖掉,泥土一块不少。生存的身体用 numen.work.dig 挖(STOP 那一下被退),
     * 创造的用 numen.use.hit 点(START 那一下被退)。
     */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_permission")
    public static void a_cancelled_break_event_refuses_the_dig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos survivalRel = new BlockPos(5, 2, 3);
        BlockPos creativeRel = new BlockPos(5, 2, 11);
        level.setBlockAndUpdate(helper.absolutePos(survivalRel), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(creativeRel), Blocks.DIRT.defaultBlockState());
        NumenPlayer digger = spawnAt(helper, "gametest_trespasser", new BlockPos(3, 2, 3), false);
        NumenPlayer builder = spawnAt(helper, "gametest_intruder", new BlockPos(3, 2, 11), true);
        BreakVeto.LOCKED.add(digger.getUUID());
        BreakVeto.LOCKED.add(builder.getUUID());
        ToolRun[] calls = new ToolRun[2];
        helper.runAfterDelay(5, () -> {
            calls[0] = lua(digger, "numen.work.dig(" + at(helper, survivalRel) + ")");
            calls[1] = click(helper, builder, "left", creativeRel);
        });

        succeedWhen(helper, () -> {
            for (ToolRun call : calls) {
                helper.assertTrue(call != null && call.done(), "a dig has not finished");
                helper.assertTrue(!call.succeeded()
                                && call.outcome().contains(com.dwinovo.numen.core.act.BlockDigger.SERVER_REFUSED),
                        "the bounced break is not reported as refused by the server: " + call.outcome());
            }
            helper.assertTrue(level.getBlockState(helper.absolutePos(survivalRel)).is(Blocks.DIRT)
                    && level.getBlockState(helper.absolutePos(creativeRel)).is(Blocks.DIRT), "the protected dirt is gone");
            BreakVeto.LOCKED.remove(digger.getUUID());
            BreakVeto.LOCKED.remove(builder.getUUID());
            CompanionFactory.despawn(level.getServer(), digger);
            CompanionFactory.despawn(level.getServer(), builder);
        });
    }

    /**
     * 在主人放的木板旁边放 TNT:危险品挨着玩家的东西要问;主人不在,问不到就不放。建造按格交代,
     * 这一格留着没动、回执说是主人没答应,TNT 还在身上。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void build_tnt_next_to_the_owners_planks_needs_the_owner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        playerPlaces(helper, new BlockPos(6, 2, 4), Items.OAK_PLANKS);
        BlockPos spot = helper.absolutePos(new BlockPos(7, 2, 4));
        NumenPlayer companion = spawnAt(helper, "gametest_demolisher", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.TNT));
        ToolRun build = lua(companion, "numen.build.place({{name = \"tnt\", pos = " + xyz(spot) + "}})");

        succeedWhen(helper, () -> {
            helper.assertTrue(build.done(), "build has not finished");
            helper.assertTrue(!build.succeeded() && build.outcome().contains("refused by the owner"),
                    "the reply does not say the owner refused that cell: " + build.outcome());
            helper.assertTrue(!level.getBlockState(spot).is(Blocks.TNT)
                            && companion.getInventory().countItem(Items.TNT) == 1,
                    "the TNT was placed next to the owner's planks");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 同一块 TNT 放在远离玩家东西的空地上:不用问,放下去就是。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void build_tnt_away_from_player_blocks_goes_ahead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spot = helper.absolutePos(new BlockPos(10, 2, 10));
        NumenPlayer companion = spawnAt(helper, "gametest_quarryman", new BlockPos(9, 2, 8), false);
        companion.getInventory().add(new ItemStack(Items.TNT));
        ToolRun build = lua(companion, "numen.build.place({{name = \"tnt\", pos = " + xyz(spot) + "}})");

        succeedWhen(helper, () -> {
            helper.assertTrue(build.done(), "build has not finished");
            helper.assertTrue(build.succeeded() && level.getBlockState(spot).is(Blocks.TNT),
                    "the TNT in the open was not placed: " + build.outcome());
            helper.assertTrue(desk(companion).pending() == null, "she asked for a placement nobody needs to allow");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ---- 打村民、打别人的狼、拆装着东西的箱子、拆活板门 ----

    /** 村民在出厂 ask 表里:主人不在,问不到就不打;attack 以主人拒绝收场,村民一滴血没掉。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void attack_a_villager_with_the_owner_away_is_refused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var villager = EntityType.VILLAGER.create(level);
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 4));
        villager.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        villager.setNoAi(true);
        level.addFreshEntity(villager);
        NumenPlayer companion = spawnAt(helper, "gametest_peacekeeper", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_SWORD));
        ToolRun attack = lua(companion, "numen.fight.attack(" + villager.getId() + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(attack.done(), "attack has not finished");
            helper.assertTrue(!attack.succeeded() && attack.outcome().contains("refused by the owner"),
                    "the refusal does not come from asking the owner: " + attack.outcome());
            helper.assertTrue(villager.getHealth() == villager.getMaxHealth(), "the villager was hit");
            villager.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 同样打村民,主人在场点了允许:征询只挂一次,允许之后才动手,村民挨了打。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void attack_a_villager_asks_then_hits_after_yes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var villager = EntityType.VILLAGER.create(level);
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 4));
        villager.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        villager.setNoAi(true);
        level.addFreshEntity(villager);
        NumenPlayer companion = spawnAt(helper, "gametest_enforcer", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_SWORD));
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_magistrate");
        ToolRun attack = lua(companion, "numen.fight.attack(" + villager.getId() + ")");
        java.util.Set<Long> requests = new java.util.HashSet<>();
        boolean[] hitBeforeYes = new boolean[1];
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            if (requests.isEmpty()) {
                hitBeforeYes[0] |= villager.getHealth() < villager.getMaxHealth();
            }
            if (pending != null && requests.add(pending.id())) {
                desk(companion).answer(pending.id(), com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(requests.size() == 1, "asked " + requests.size() + " times about the villager");
            helper.assertTrue(!hitBeforeYes[0], "the villager was hit before the owner said yes");
            helper.assertTrue(!villager.isAlive() || villager.getHealth() < villager.getMaxHealth(),
                    "the villager was not hit after the owner said yes: " + attack.outcome());
            villager.discard();
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /** 别人养的狼(有主人):打死了就是人家的宠物没了,要问;主人不在就不打,狼一滴血没掉。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void attack_someones_tamed_wolf_is_refused_without_the_owner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var wolf = EntityType.WOLF.create(level);
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 4));
        wolf.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        wolf.setTame(true, false);
        wolf.setOwnerUUID(UUID.randomUUID());
        wolf.setNoAi(true);
        level.addFreshEntity(wolf);
        NumenPlayer companion = spawnAt(helper, "gametest_dogcatcher", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_SWORD));
        ToolRun attack = lua(companion, "numen.fight.attack(" + wolf.getId() + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(attack.done(), "attack has not finished");
            helper.assertTrue(!attack.succeeded() && attack.outcome().contains("refused by the owner"),
                    "the refusal does not come from asking the owner: " + attack.outcome());
            helper.assertTrue(wolf.getHealth() == wolf.getMaxHealth(), "the tamed wolf was hit");
            wolf.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 装着钻石的箱子不是玩家放的也一样:拆了东西会洒,要问;主人不在就不挖,箱子和五颗钻石都在。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void mine_a_chest_with_things_in_it_needs_the_owner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = chestWithDiamonds(helper, new BlockPos(6, 2, 4), 5);
        NumenPlayer companion = spawnAt(helper, "gametest_looter", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        Mining mine = mineScanned(helper, companion, 8, "minecraft:chest", 1);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "mine has not finished");
            helper.assertTrue(!mine.succeeded() && mine.outcome().contains("refused by the owner"),
                    "the refusal does not come from asking the owner: " + mine.outcome());
            helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST)
                            && level.getBlockEntity(chest) instanceof net.minecraft.world.Container box
                            && box.countItem(Items.DIAMOND) == 5,
                    "the chest or its diamonds are gone");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 活板门出厂就在 ask 表里(门、床、栅栏门同理):主人不在就不挖,活板门还在。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void mine_a_trapdoor_needs_the_owner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos trapdoor = helper.absolutePos(new BlockPos(6, 2, 4));
        level.setBlockAndUpdate(trapdoor, Blocks.OAK_TRAPDOOR.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_doorman", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        Mining mine = mineScanned(helper, companion, 8, "minecraft:oak_trapdoor", 1);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "mine has not finished");
            helper.assertTrue(!mine.succeeded() && mine.outcome().contains("refused by the owner"),
                    "the refusal does not come from asking the owner: " + mine.outcome());
            helper.assertTrue(level.getBlockState(trapdoor).is(Blocks.OAK_TRAPDOOR), "the trapdoor was broken");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 拿水桶对着主人木板旁边的地面右键:倒水是危险品挨着玩家的东西,要问;主人不在,问不到就不倒——
     * 走的是 numen.use.block 的放置路径,和 build 一样由权限层裁决。水没倒出来,桶还是满的。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void interact_at_water_next_to_the_owners_planks_needs_the_owner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        playerPlaces(helper, new BlockPos(6, 2, 4), Items.OAK_PLANKS);
        BlockPos floor = helper.absolutePos(new BlockPos(6, 1, 5));
        NumenPlayer companion = spawnAt(helper, "gametest_waterer", new BlockPos(4, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.WATER_BUCKET));
        ToolRun pour = lua(companion, "numen.use.block(" + xyz(floor) + ", {item = \"minecraft:water_bucket\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(pour.done(), "use block has not finished");
            helper.assertTrue(!pour.succeeded() && pour.outcome().contains("refused by the owner"),
                    "the refusal does not come from asking the owner: " + pour.outcome());
            helper.assertTrue(!level.getBlockState(floor.above()).is(Blocks.WATER)
                            && companion.getInventory().countItem(Items.WATER_BUCKET) == 1,
                    "the water was poured next to the owner's planks");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 主人用命令写 {@code deny break(placed)}:收下这一行。她 numen.use.hit 左键打玩家放的石头被拒、理由是那一行规则,石头还在;
     * 天然的石头照常挖掉。从头到尾不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void a_placed_deny_row_guards_what_players_placed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos placedRel = new BlockPos(6, 2, 5);
        BlockPos naturalRel = new BlockPos(6, 2, 3);
        playerPlaces(helper, placedRel, Items.STONE);
        level.setBlockAndUpdate(helper.absolutePos(naturalRel), Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_house_sitter", new BlockPos(4, 2, 5), true);
        ServerPlayer owner = presentPlayer(helper, companion, "gametest_house_owner");

        List<String> added = runAs(owner, "numen permission rules add deny break(placed)");
        helper.assertTrue(added.stream().anyMatch(m -> m.contains("Added to deny")), "the row was not added: " + added);

        ToolRun[] calls = new ToolRun[2];
        int[] step = {0};
        boolean[] asked = new boolean[1];
        helper.runAfterDelay(5, () -> {
            calls[0] = click(helper, companion, "left", placedRel);
            step[0] = 1;
        });
        helper.onEachTick(() -> {
            asked[0] |= desk(companion).pending() != null;
            if (step[0] == 1 && calls[0].done()) {
                calls[1] = click(helper, companion, "left", naturalRel);
                step[0] = 2;
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(step[0] == 2 && calls[1].done(), "the two clicks have not finished");
            String refused = calls[0].outcome();
            helper.assertTrue(!calls[0].succeeded() && refused.contains("denied by rule break(placed)"),
                    "the dig of the placed stone was not refused by the row: " + refused);
            helper.assertTrue(level.getBlockState(helper.absolutePos(placedRel)).is(Blocks.STONE),
                    "the placed stone was dug");
            helper.assertTrue(calls[1].succeeded()
                            && level.getBlockState(helper.absolutePos(naturalRel)).isAir(),
                    "the natural stone was not dug: " + calls[1].outcome());
            helper.assertTrue(!asked[0], "a deny row raised a consent card");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 主人不许挖玩家放的:谷仓里玩家摆的南瓜离她近,野地里长的远,一次扫描两个都找到。要一个南瓜,{@code arrive = "dig"} 只去挖得成
     * 的那个,她挖野地里那个,谷仓里的一动不动;对同样的方块再要一个,只剩谷仓里的,当场拒绝、理由是那一行规则。不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void work_dig_leaves_the_denied_placed_blocks_standing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos insideRel = new BlockPos(5, 2, 5);
        BlockPos outsideRel = new BlockPos(11, 2, 5);
        playerPlaces(helper, insideRel, Items.PUMPKIN);
        level.setBlockAndUpdate(helper.absolutePos(outsideRel), Blocks.PUMPKIN.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_harvester", new BlockPos(2, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        ServerPlayer owner = presentPlayer(helper, companion, "gametest_farmer");
        storeOf(owner).add(com.dwinovo.numen.permission.Verdict.Kind.DENY,
                com.dwinovo.numen.permission.Rule.parse("break(placed)"));
        Mining first = mineScanned(helper, companion, 10, "minecraft:pumpkin", 1);
        Mining[] second = new Mining[1];
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            helper.assertTrue(first.done(), "the first mine has not finished");
            helper.assertTrue(first.succeeded() && level.getBlockState(helper.absolutePos(outsideRel)).isAir(),
                    "the wild pumpkin was not mined: " + first.outcome());
            helper.assertTrue(level.getBlockState(helper.absolutePos(insideRel)).is(Blocks.PUMPKIN),
                    "the placed pumpkin in the barn was mined");
            if (second[0] == null) {
                second[0] = mine(companion, first.blocks(), 1);
            }
            helper.assertTrue(second[0].done(), "the second mine has not finished");
            helper.assertTrue(!second[0].succeeded() && second[0].outcome().contains("break(placed)"),
                    "the second mine does not stop on the placed row: " + second[0].outcome());
            helper.assertTrue(level.getBlockState(helper.absolutePos(insideRel)).is(Blocks.PUMPKIN),
                    "the placed pumpkin in the barn was mined");
            helper.assertTrue(!asked[0], "a deny row raised a consent card");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 挡着视线的格不许挖,她就换一面看:南瓜在一堵两格高的木板墙后面,墙是玩家砌的、主人不许挖玩家放的,墙向两边伸出
     * 四格。站在墙前最近,手也够得着,可看得见南瓜的每一面都隔着墙;她绕过墙头,从看得见它的那一面挖到手。墙一块不少,不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void work_dig_goes_around_a_denied_wall_to_see_the_block(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pumpkin = helper.absolutePos(new BlockPos(8, 2, 5));
        level.setBlockAndUpdate(pumpkin, Blocks.PUMPKIN.defaultBlockState());
        List<BlockPos> wall = new ArrayList<>();
        for (int z = 1; z <= 9; z++) {
            for (int y = 2; y <= 3; y++) {
                BlockPos rel = new BlockPos(7, y, z);
                playerPlaces(helper, rel, Items.OAK_PLANKS);
                wall.add(helper.absolutePos(rel));
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_fence_walker", new BlockPos(2, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        ServerPlayer owner = presentPlayer(helper, companion, "gametest_fencer");
        storeOf(owner).add(com.dwinovo.numen.permission.Verdict.Kind.DENY,
                com.dwinovo.numen.permission.Rule.parse("break(placed)"));
        Mining mine = mineScanned(helper, companion, 10, "minecraft:pumpkin", 1);
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "the dig has not finished");
            helper.assertTrue(mine.succeeded() && level.getBlockState(pumpkin).isAir()
                            && companion.getInventory().countItem(Items.PUMPKIN) == 1,
                    "the pumpkin behind the denied wall was not dug from another side: " + mine.outcome());
            for (BlockPos cell : wall) {
                helper.assertTrue(level.getBlockState(cell).is(Blocks.OAK_PLANKS),
                        "the denied wall was broken at " + cell.toShortString());
            }
            helper.assertTrue(!asked[0], "a deny row raised a consent card");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 唯一的视线被不许挖的格挡死:南瓜六面都贴着白羊毛,主人写了 deny 行不许挖白羊毛。站位怎么挑都看不见它,当场回:南瓜的
     * 每一面都贴着不许挖的方块,点名是哪几格、哪一行规则。羊毛与南瓜原样,不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void work_dig_names_the_denied_blocks_walling_in_its_target(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pumpkin = helper.absolutePos(new BlockPos(8, 2, 5));
        level.setBlockAndUpdate(pumpkin, Blocks.PUMPKIN.defaultBlockState());
        List<BlockPos> wool = new ArrayList<>();
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            BlockPos cell = pumpkin.relative(side);
            level.setBlockAndUpdate(cell, Blocks.WHITE_WOOL.defaultBlockState());
            wool.add(cell);
        }
        NumenPlayer companion = spawnAt(helper, "gametest_walled_out", new BlockPos(2, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        ServerPlayer owner = presentPlayer(helper, companion, "gametest_weaver");
        storeOf(owner).add(com.dwinovo.numen.permission.Verdict.Kind.DENY,
                com.dwinovo.numen.permission.Rule.parse("break(minecraft:white_wool)"));
        Mining mine = mineScanned(helper, companion, 10, "minecraft:pumpkin", 1);
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "the dig has not finished");
            String said = mine.outcome();
            helper.assertTrue(mine.task() == null && !mine.succeeded(),
                    "a dig whose only target is walled in was accepted: " + mine.reply());
            helper.assertTrue(said.contains("every face of pumpkin at " + Listing.coords(pumpkin)
                                    + " is covered by a block I may not break")
                            && wool.stream().allMatch(cell -> said.contains("white_wool at " + Listing.coords(cell)))
                            && said.contains("denied by rule break(minecraft:white_wool)"),
                    "the reply does not name the denied blocks walling the pumpkin in and the row: " + said);
            helper.assertTrue(level.getBlockState(pumpkin).is(Blocks.PUMPKIN), "the pumpkin was mined");
            for (BlockPos cell : wool) {
                helper.assertTrue(level.getBlockState(cell).is(Blocks.WHITE_WOOL),
                        "the denied wool was broken at " + cell.toShortString());
            }
            helper.assertTrue(!asked[0], "a denied block raised a consent card");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 挡着的是要主人同意才能动的格:南瓜在她手边,六面都贴着别的玩家放的木板。{@code numen.work.dig} 只挖开天然地形,要问主人的
     * 遮挡不挖、也不替她去问,当场回:南瓜的每一面都贴着她不能挖的方块,点名是哪几格、为什么(要主人同意)。木板与南瓜原样,
     * 不弹卡,她一步没动。
     */
    @GameTest(template = "floor16", timeoutTicks = 4000, batch = "numen_permission")
    public static void work_dig_names_the_blocks_needing_consent_walling_in_its_target(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pumpkin = helper.absolutePos(new BlockPos(8, 2, 5));
        level.setBlockAndUpdate(pumpkin, Blocks.PUMPKIN.defaultBlockState());
        var neighbour = new com.dwinovo.numen.permission.PlacedBlocks.Placer(UUID.randomUUID(), "gametest_carpenter");
        List<BlockPos> planks = new ArrayList<>();
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            BlockPos cell = pumpkin.relative(side);
            level.setBlockAndUpdate(cell, Blocks.OAK_PLANKS.defaultBlockState());
            com.dwinovo.numen.permission.PlacedBlocks.of(level).record(cell, neighbour);
            planks.add(cell);
        }
        NumenPlayer companion = spawnAt(helper, "gametest_boxed_out", new BlockPos(5, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_joiner");
        BlockPos stand = companion.blockPosition();
        ToolRun dig = lua(companion, "numen.work.dig(" + xyz(pumpkin) + ")");
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            helper.assertTrue(dig.done(), "work dig has not answered");
            String said = dig.outcome();
            helper.assertTrue(dig.task() == null && !dig.succeeded(),
                    "a dig whose only target is walled in by blocks needing consent was accepted: " + said);
            helper.assertTrue(said.contains("every face of pumpkin at " + Listing.coords(pumpkin)
                                    + " is covered by a block I may not break")
                            && planks.stream().allMatch(cell -> said.contains("oak_planks at " + Listing.coords(cell)))
                            && said.contains("changing it needs the owner's consent"),
                    "the reply does not name the blocks walling the pumpkin in and why: " + said);
            helper.assertTrue(level.getBlockState(pumpkin).is(Blocks.PUMPKIN), "the pumpkin was dug");
            for (BlockPos cell : planks) {
                helper.assertTrue(level.getBlockState(cell).is(Blocks.OAK_PLANKS),
                        "a plank needing consent was broken at " + cell.toShortString());
            }
            helper.assertTrue(!asked[0], "a block needing consent in the way raised a consent card");
            helper.assertTrue(companion.blockPosition().equals(stand), "she moved");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }
}
