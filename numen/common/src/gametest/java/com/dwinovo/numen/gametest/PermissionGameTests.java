package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.TaskRecord;
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

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 权限层:主人的东西要问、分团挖掘、记住的同意与规则分层、右键与拿东西、原生通道上的退回。原版里方块没有主人,用例里"主人的""别人的"
 * 方块由一个方块归属的回答者说({@link GameTestKit#claim}),和领地模组的插件是同一条路。
 */
public class PermissionGameTests {

    /** 权限批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_permission")
    public static void preparePermissionBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 没登录的真玩家身份:裸的 ServerPlayer,不走登录(登录会给这个没有客户端的假人推同伴名册的载荷),只当一个人的身份用。
     * 不在线,所以他若是她的主人,主人就不在场。
     */
    private static ServerPlayer barePlayer(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        return new ServerPlayer(level.getServer(), level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), name),
                net.minecraft.server.level.ClientInformation.createDefault());
    }

    /** 请同伴认这位玩家作主人:他的东西对她来说就是主人的。 */
    private static NumenPlayer adopted(NumenPlayer companion, ServerPlayer owner) {
        companion.setOwnerUuid(owner.getUUID());
        return companion;
    }

    /**
     * 在 {@code rels} 各摆一块 {@code block},并说这几格是 {@code who} 的:是她的主人,对她就是主人的;是别的玩家,就是别人的。
     */
    private static void ownedBlocks(GameTestHelper helper, UUID who, List<BlockPos> rels,
                                    net.minecraft.world.level.block.Block block) {
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos rel : rels) {
            BlockPos cell = helper.absolutePos(rel);
            helper.getLevel().setBlockAndUpdate(cell, block.defaultBlockState());
            cells.add(cell);
        }
        claim(helper, who, cells);
    }

    private static com.dwinovo.numen.api.permission.ConsentDesk desk(NumenPlayer companion) {
        return com.dwinovo.numen.api.permission.ConsentDesk.of(companion);
    }

    /** 一间木板屋,四面墙与脚下的地板都是这位主人的(一间房子的地板也是主人铺的)。 */
    private static void ownersRoom(GameTestHelper helper, int cx, int cz, UUID ownerId) {
        plankRoomAround(helper, cx, cz);
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = new ArrayList<>();
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                for (int y = 1; y <= 4; y++) {
                    BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
                    if (!level.getBlockState(pos).isAir()) {
                        cells.add(pos);
                    }
                }
            }
        }
        claim(helper, ownerId, cells);
    }

    /** 一根野树干:两格原木,顶上一块自然长出的树叶。没人说它是谁的。 */
    private static void wildLog(GameTestHelper helper, BlockPos rel, net.minecraft.world.level.block.Block log) {
        ServerLevel level = helper.getLevel();
        level.setBlockAndUpdate(helper.absolutePos(rel), log.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(rel.above()), log.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(rel.above(2)), GameTestKit.naturalLeaves());
    }

    /** 主人的屋子西半边那一格:要出去得先走过屋里两格,再挖东墙。 */
    private static final BlockPos WEST_INSIDE = new BlockPos(6, 2, 7);

    /**
     * 越过边界才问:穿主人的一堵墙的那条路照常规划、照常开走——不在开走前问。她走过屋里那两格,走到墙前要挖它时才停下挂一条征询,
     * 这时墙一块不少;征询列出这一声答应放行的每一格(同一种木板、同一行规则),之后不再问;主人允许,她拆墙出去到达,
     * 回执说主人允许过,路程回执的裁定列表里就是那几格。
     */
    @GameTest(template = "floor16", timeoutTicks = 504, batch = "numen_permission")
    public static void a_walk_asks_at_the_owners_wall_and_goes_on_when_allowed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, null, "gametest_host");
        ownersRoom(helper, 7, 7, owner.getUUID());
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = adopted(spawnAt(helper, "gametest_tenant", WEST_INSIDE, false), owner);
        BlockPos start = companion.blockPosition();
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun walk = lua(companion, "return numen.move.walk(" + xyz(target) + ")");
        boolean[] answered = new boolean[1];
        int[] listed = new int[1];
        int[] requests = new int[1];

        succeedWhen(helper, () -> {
            TaskRecord<?> record = walk.task();
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
                        com.dwinovo.numen.api.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
            }
            if (desk(companion).pending() != null) {
                // 墙的其余几格同一种木板,第一声答应已经放行,不该再问;真挂了就记下再答一次,让下面的断言说清
                requests[0]++;
                desk(companion).answer(desk(companion).pending().id(),
                        com.dwinovo.numen.api.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
            }
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "the walk has not finished");
            helper.assertTrue(record.getResult().success(), "the walk failed after the owner allowed it: " + reply);
            helper.assertTrue(walk.receipt() != null, "the program has not handed in its receipt");
            com.google.gson.JsonArray rulings = walk.data().getAsJsonObject("returned").getAsJsonArray("rulings");
            helper.assertTrue(listed[0] >= 2 && rulings.size() == listed[0]
                            && rulings.toString().contains("\"allowed\":true"),
                    "the account does not list every cell the yes lets through (" + listed[0] + "): " + walk.receipt());
            helper.assertTrue(requests[0] == 1, "the owner was asked " + requests[0] + " times");
            helper.assertTrue(plankCount(helper, 7, 7) < planksBefore, "no plank was broken");
            helper.assertTrue(reply.contains("the owner allowed"), "the reply does not say the owner allowed it: " + reply);
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 同一条路主人说不:走到墙前问,主人不答应,那几格这一趟里当墙、重新规划;屋子四面都是主人的墙,绕不开,她以 denied 收场,
     * 回执里有主人的原话,同一种东西不再问第二遍;墙一块不少,她没出屋。
     */
    @GameTest(template = "floor16", timeoutTicks = 375, batch = "numen_permission")
    public static void a_walk_refused_at_the_owners_wall_stops_there(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, null, "gametest_strict");
        ownersRoom(helper, 7, 7, owner.getUUID());
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = adopted(spawnAt(helper, "gametest_squatter", WEST_INSIDE, false), owner);
        BlockPos start = companion.blockPosition();
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun walk = lua(companion, "numen.move.walk(" + xyz(target) + ")");
        boolean[] answered = new boolean[1];
        int[] cards = new int[1];

        succeedWhen(helper, () -> {
            TaskRecord<?> record = walk.task();
            var pending = desk(companion).pending();
            if (pending != null) {
                cards[0]++;
                answered[0] = desk(companion).answer(pending.id(),
                        com.dwinovo.numen.api.permission.ConsentAnswer.Decision.DENY, "别拆我的墙");
            }
            String reply = record == null || record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "the walk has not finished");
            helper.assertTrue(!record.getResult().success() && "denied".equals(walk.kind())
                    && reply.contains("your owner: 别拆我的墙") && reply.contains("kept out of this walk"),
                    "the refusal does not quote the owner: " + reply);
            helper.assertTrue(answered[0] && cards[0] == 1, "the owner was asked " + cards[0] + " times for the same kind of wall");
            helper.assertTrue(plankCount(helper, 7, 7) == planksBefore, "the wall was damaged after a no");
            helper.assertTrue(companion.blockPosition().distSqr(target) > 3 * 3,
                    "she got out through the wall though the owner refused: " + companion.blockPosition().toShortString());
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * mine 挖到主人的原木就问,同种原木只问一次:附近只有两根主人的橡木,要两根。卡片挂上,
     * 主人允许,她把两根都挖了——第二根不再弹卡(同一行规则问出来的同一种方块本任务内已授权)。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void mine_asks_once_for_the_owners_logs_then_mines(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> logs = List.of(new BlockPos(5, 2, 4), new BlockPos(5, 2, 6));
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, null, "gametest_forester");
        ownedBlocks(helper, owner.getUUID(), logs, Blocks.OAK_LOG);
        NumenPlayer companion = adopted(spawnAt(helper, "gametest_lumberjack", new BlockPos(2, 2, 5), false), owner);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        Mining mine = mineScanned(helper, companion, 8, "minecraft:oak_log", 2);
        java.util.Set<Long> requests = new java.util.HashSet<>();
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            if (pending != null && requests.add(pending.id())) {
                desk(companion).answer(pending.id(), com.dwinovo.numen.api.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
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

    /**
     * 同一套定价自然排序:主人的原木离她三格,野树九格。要两根——她走去砍野树,主人的原木
     * 一根不少,从头到尾没有弹过一张卡。没有剔除,主人的原木只是贵(需要同意的格乘十倍)。
     */
    @GameTest(template = "floor16", timeoutTicks = 285, batch = "numen_permission")
    public static void mine_prefers_wild_trees_by_price(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> owners = List.of(new BlockPos(5, 2, 4), new BlockPos(5, 2, 6));
        List<BlockPos> wild = List.of(new BlockPos(11, 2, 4), new BlockPos(11, 2, 6));
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, null, "gametest_warden");
        ownedBlocks(helper, owner.getUUID(), owners, Blocks.ACACIA_LOG);
        for (BlockPos rel : wild) {
            wildLog(helper, rel, Blocks.ACACIA_LOG);
        }
        NumenPlayer companion = adopted(spawnAt(helper, "gametest_ranger", new BlockPos(2, 2, 5), false), owner);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        Mining mine = mineScanned(helper, companion, 12, "minecraft:acacia_log", 2);
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            helper.assertTrue(mine.done(), "mine has not finished");
            helper.assertTrue(companion.getInventory().countItem(Items.ACACIA_LOG) >= 2,
                    "companion has not gathered 2 logs: " + mine.outcome());
            for (BlockPos rel : owners) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.ACACIA_LOG),
                        "one of the owner's logs was cut while wild ones stood nearby at " + rel.toShortString());
            }
            helper.assertTrue(!asked[0], "asked the owner although wild logs were there");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /** 挖这一团,挖完为止({@link GameTestKit#mine(NumenPlayer, String, int)}):团的方块原样写进程序。 */
    private static Mining mineCluster(NumenPlayer companion, com.google.gson.JsonObject cluster) {
        return mine(companion, blocksOf(cluster), 0);
    }

    /**
     * 交给她的那一团被拒就停:主人写了 deny 行不许挖绯红菌柄;扫描只说找到了什么,不说主人许不许。mine 那一团,派发当场按拒绝收场、
     * 理由是那一行规则,菌柄一根不少,也不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
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
                com.dwinovo.numen.api.permission.Groups.parse("break(minecraft:crimson_stem)"));
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
     * 她一路往下挖掉的点名格都算她挖的:四块黏土叠成一柱,四面黑曜石围成竖井,她站在柱顶。交出这一团,脚下那一块挖掉
     * 她就落下去、接着挖下一块,为看见下面那块挖开的遮挡本身也是点名格。四格都记成她挖的,没有一格记成"别人动过"。
     */
    @GameTest(template = "floor16", timeoutTicks = 318, batch = "numen_permission")
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
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.CLAY.defaultBlockState());
        }
        // 原版 GameTest 给测试结构封了一层屏障顶,正好压在竖井里她头顶那一格:竖井上半截明确清空,她才站得直
        for (int y = 6; y <= 9; y++) {
            level.setBlockAndUpdate(helper.absolutePos(new BlockPos(7, y, 7)), Blocks.AIR.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_sinker", new BlockPos(7, 6, 7), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        ToolRun reply = scan(companion, 6, "minecraft:clay");
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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void interact_left_click_on_owners_chest_asks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chestRel = new BlockPos(6, 2, 5);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, null, "gametest_hoarder");
        ownedBlocks(helper, owner.getUUID(), List.of(chestRel), Blocks.CHEST);
        BlockPos chest = helper.absolutePos(chestRel);
        NumenPlayer companion = adopted(spawnAt(helper, "gametest_poker", new BlockPos(4, 2, 5), true), owner);
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
                        com.dwinovo.numen.api.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
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
        TaskRecord<?> record = lua(companion, "numen.fight.attack(" + ref(pig) + ")").task();
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
                        com.dwinovo.numen.api.permission.ConsentAnswer.Decision.DENY, "");
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(denied[0] && record.getResult() != null, "attack has not finished after the no");
            helper.assertTrue(pig.isAlive(), "the pig was hit after the owner said no");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 卡片挂着时主人按停止:numen.inv.drop 悬着等答复,主人没点卡片而是按了停止(与停止键同一个入口)。
     * 这件活按主人停止收场、消息写明是主人停的,挂着的征询随之撤掉,东西一件没丢。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
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
                com.dwinovo.numen.api.task.CompanionTickDispatcher.cancelFor(companion);
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

    /** 以玩家 {@code who} 的身份跑一条命令,和在聊天栏里敲的一样;回话(成功与失败的)收进返回的列表。 */
    private static List<String> runAs(ServerPlayer who, String command) {
        List<String> said = new ArrayList<>();
        net.minecraft.commands.CommandSource capture =
                com.dwinovo.numen.api.gametest.GameTestWorld.hearing(message -> said.add(message.getString()));
        who.getServer().getCommands().performPrefixedCommand(who.createCommandSourceStack().withSource(capture),
                command);
        return said;
    }

    private static com.dwinovo.numen.api.permission.PermissionStore storeOf(ServerPlayer owner) {
        return com.dwinovo.numen.api.permission.PermissionStore.of(owner.getServer(), owner.getUUID());
    }

    /**
     * {@code /numen consent} 与卡片是同一个入口:第一次丢钻石由卡片的网络载荷答复,第二次丢绿宝石由主人敲命令
     * "允许并记住"答复——两次都丢了、回执都交代主人允许了(记住的那次还交代记下了哪一行);记住之后第三次丢绿宝石
     * 不再问。带附言的允许不收(附言只随拒绝);别人敲命令答不了;
     * 答一个没挂着的号说清楚没有。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
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
                        com.dwinovo.numen.api.network.payload.ConsentReplyPayload.handle(
                                new com.dwinovo.numen.api.network.payload.ConsentReplyPayload(companion.getUUID(),
                                        pending.id(), com.dwinovo.numen.api.permission.ConsentAnswer.Decision.ALLOW_ONCE,
                                        "小心点"), owner);
                        helper.assertTrue(desk(companion).pending() == pending,
                                "an allow carrying a note was taken; a note only goes with a deny");
                        com.dwinovo.numen.api.network.payload.ConsentReplyPayload.handle(
                                new com.dwinovo.numen.api.network.payload.ConsentReplyPayload(companion.getUUID(),
                                        pending.id(), com.dwinovo.numen.api.permission.ConsentAnswer.Decision.ALLOW_ONCE,
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
     * 记住的规则按活世界推:没主人的空箱子(带方块实体)问的是 {@code break(block_entity)},记下的是
     * {@code break(block_entity & minecraft:chest & !contents)};装着东西了,记下的就不钉 {@code !contents}、盖得住装着东西的;
     * 有名字的狼问的是 {@code attack(named)},记下的只认这一只。
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

        var gate = com.dwinovo.numen.api.permission.Permission.gateFor(companion);
        var digEmpty = com.dwinovo.numen.api.permission.Action.breakBlock(chest, level.getBlockState(chest));
        var emptyVerdict = gate.judgeLive(digEmpty, level);
        helper.assertTrue(emptyVerdict.asks() && emptyVerdict.rule().toString().equals("break(block_entity)"),
                "an empty chest that is nobody's: " + emptyVerdict);
        String emptyRow = gate.consentItemLive(digEmpty, emptyVerdict, level).remember().toString();
        helper.assertTrue(emptyRow.equals("break(block_entity & minecraft:chest & !contents)"), "remembered " + emptyRow);

        var attack = com.dwinovo.numen.api.permission.Action.attack(wolf);
        var wolfVerdict = gate.judgeLive(attack, level);
        helper.assertTrue(wolfVerdict.asks() && wolfVerdict.rule().toString().equals("attack(named)"),
                "a named wolf: " + wolfVerdict);
        String wolfRow = gate.consentItemLive(attack, wolfVerdict, level).remember().toString();
        helper.assertTrue(wolfRow.equals("attack(entity:" + wolf.getUUID() + ")"), "remembered " + wolfRow);

        ((net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chest))
                .setItem(0, new ItemStack(Items.DIAMOND));
        var digFull = com.dwinovo.numen.api.permission.Action.breakBlock(chest, level.getBlockState(chest));
        var fullVerdict = gate.judgeLive(digFull, level);
        String fullRow = gate.consentItemLive(digFull, fullVerdict, level).remember().toString();
        helper.assertTrue(fullRow.equals("break(block_entity & minecraft:chest)"), "remembered " + fullRow);

        wolf.discard();
        CompanionFactory.despawn(level.getServer(), companion);
        helper.succeed();
    }

    /**
     * 只有玩家做主人才算有主:主人是一只活着的唤魔者(它的随从)的狼,没有人的主人,按野生的放行;
     * 主人找不到(离线的玩家)的狼仍是别人的,硬规则拒绝。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void only_a_player_owner_makes_an_entity_someones(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(6, 2, 9));
        var evoker = EntityType.EVOKER.create(level);
        var minion = EntityType.WOLF.create(level);
        var pet = EntityType.WOLF.create(level);
        helper.assertTrue(evoker != null && minion != null && pet != null, "entities did not spawn");
        evoker.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        evoker.setNoAi(true);
        level.addFreshEntity(evoker);
        minion.moveTo(at.getX() + 2.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        minion.setNoAi(true);
        minion.setOwnerUUID(evoker.getUUID());
        level.addFreshEntity(minion);
        pet.moveTo(at.getX() + 4.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pet.setNoAi(true);
        pet.setOwnerUUID(java.util.UUID.randomUUID());
        level.addFreshEntity(pet);
        NumenPlayer companion = spawnAt(helper, "gametest_hunter", new BlockPos(3, 2, 5), false);

        var gate = com.dwinovo.numen.api.permission.Permission.gateFor(companion);
        var minionVerdict = gate.judgeLive(com.dwinovo.numen.api.permission.Action.attack(minion), level);
        helper.assertTrue(minionVerdict.allowed(), "a wolf owned by an evoker is nobody's: " + minionVerdict);
        var petVerdict = gate.judgeLive(com.dwinovo.numen.api.permission.Action.attack(pet), level);
        helper.assertTrue(!petVerdict.allowed() && !petVerdict.asks(),
                "a wolf owned by an absent player is someone's: " + petVerdict);

        evoker.discard();
        minion.discard();
        pet.discard();
        CompanionFactory.despawn(level.getServer(), companion);
        helper.succeed();
    }

    /**
     * 主人写 {@code ask use_block(contents)} 之后,右键打开装着东西的箱子要问(出厂规则对没主人的箱子是放行的):numen.container.take
     * 悬着、箱子没打开、钻石还在里面;主人允许后箱子打开,往外拿不再另问,钻石进了她的背包。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void an_owners_ask_row_on_opening_a_full_container_asks_before_taking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = chestWithDiamonds(helper, new BlockPos(6, 2, 5), 3);
        NumenPlayer companion = spawnAt(helper, "gametest_borrower", new BlockPos(4, 2, 5), false);
        ServerPlayer owner = presentPlayer(helper, companion, "gametest_lender");
        runAs(owner, "numen permission rules add ask use_block(contents)");
        ToolRun take = lua(companion, "numen.container.take(" + xyz(chest) + ", \"minecraft:diamond\")");
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            if (pending != null && !asked[0]) {
                helper.assertTrue(pending.items().size() == 1 && pending.items().get(0).rule().equals("use_block(contents)")
                                && pending.items().get(0).subject().equals("chest"),
                        "asked something else: " + pending.items());
                helper.assertTrue(!take.done(), "the take did not wait for the owner");
                helper.assertTrue(companion.containerMenu == companion.inventoryMenu,
                        "the chest was opened before the owner answered");
                helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 0,
                        "took before the owner answered");
                desk(companion).answer(pending.id(),
                        com.dwinovo.numen.api.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
                asked[0] = true;
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(asked[0] && take.done(), "the take has not finished");
            helper.assertTrue(take.succeeded(), "the take failed after allow: " + take.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 3, "the diamonds did not arrive");
            var box = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chest);
            helper.assertTrue(box.getItem(0).isEmpty(), "the chest still holds the diamonds");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 服务端退回了挖掘(快捷栏塞满调试棒,空不出手来;原版不许调试棒破坏方块,{@code Item.canAttackBlock}):权限层放行了(没主人的方块),挖掘落点照真客户端挖下去,
     * 服务端退回来——以 refused 收场,理由写明服务器没让挖掉,方块一块不少。生存的身体用 numen.work.dig 挖:STOP 之后方块弹回,
     * 照原版客户端同一格再挖一轮,第二次 STOP 之后还在才算没让挖;创造的用 numen.use.hit 点(START 那一下被退)。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void a_bounced_dig_is_reported_as_refused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos survivalRel = new BlockPos(5, 2, 3);
        BlockPos creativeRel = new BlockPos(5, 2, 11);
        level.setBlockAndUpdate(helper.absolutePos(survivalRel), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(creativeRel), Blocks.DIRT.defaultBlockState());
        NumenPlayer digger = spawnAt(helper, "gametest_trespasser", new BlockPos(3, 2, 3), false);
        NumenPlayer builder = spawnAt(helper, "gametest_intruder", new BlockPos(3, 2, 11), true);
        for (int slot = 0; slot < 9; slot++) {
            digger.getInventory().setItem(slot, new ItemStack(Items.DEBUG_STICK));
            builder.getInventory().setItem(slot, new ItemStack(Items.DEBUG_STICK));
        }
        ToolRun[] calls = new ToolRun[2];
        helper.runAfterDelay(5, () -> {
            calls[0] = lua(digger, "numen.work.dig(" + at(helper, survivalRel) + ")");
            calls[1] = click(helper, builder, "left", creativeRel);
        });

        succeedWhen(helper, () -> {
            for (ToolRun call : calls) {
                helper.assertTrue(call != null && call.done(), "a dig has not finished");
                helper.assertTrue(!call.succeeded()
                                && call.outcome().contains(com.dwinovo.numen.act.BlockDigger.SERVER_REFUSED),
                        "the bounced break is not reported as refused by the server: " + call.outcome());
            }
            helper.assertTrue(level.getBlockState(helper.absolutePos(survivalRel)).is(Blocks.DIRT)
                    && level.getBlockState(helper.absolutePos(creativeRel)).is(Blocks.DIRT), "the protected dirt is gone");
            CompanionFactory.despawn(level.getServer(), digger);
            CompanionFactory.despawn(level.getServer(), builder);
        });
    }

    /**
     * 在主人的木板旁边放 TNT:危险品挨着主人的东西要问;主人不在,问不到就不放。建造按格交代,
     * 这一格留着没动、回执说是主人没答应,TNT 还在身上。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void build_tnt_next_to_the_owners_planks_needs_the_owner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer absentOwner = barePlayer(helper, "gametest_absent_owner");
        ownedBlocks(helper, absentOwner.getUUID(), List.of(new BlockPos(6, 2, 4)), Blocks.OAK_PLANKS);
        BlockPos spot = helper.absolutePos(new BlockPos(7, 2, 4));
        NumenPlayer companion = adopted(spawnAt(helper, "gametest_demolisher", new BlockPos(3, 2, 4), false), absentOwner);
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

    // ---- 打村民、打别人的狼、拆装着东西的箱子、拆活板门 ----

    /** 同样打村民,主人在场点了允许:征询只挂一次,允许之后才动手,村民挨了打。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
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
        ToolRun attack = lua(companion, "numen.fight.attack(" + ref(villager) + ")");
        java.util.Set<Long> requests = new java.util.HashSet<>();
        boolean[] hitBeforeYes = new boolean[1];
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            if (requests.isEmpty()) {
                hitBeforeYes[0] |= villager.getHealth() < villager.getMaxHealth();
            }
            if (pending != null && requests.add(pending.id())) {
                desk(companion).answer(pending.id(), com.dwinovo.numen.api.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
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

    /** 主人养的狼:打死了就是他的宠物没了,要问;主人不在就不打,狼一滴血没掉。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void attack_the_owners_tamed_wolf_is_refused_without_the_owner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var wolf = EntityType.WOLF.create(level);
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 4));
        wolf.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        wolf.setTame(true, false);
        NumenPlayer companion = spawnAt(helper, "gametest_dogsitter", new BlockPos(3, 2, 4), false);
        wolf.setOwnerUUID(companion.getOwnerUuid());
        wolf.setNoAi(true);
        level.addFreshEntity(wolf);
        companion.getInventory().add(new ItemStack(Items.IRON_SWORD));
        ToolRun attack = lua(companion, "numen.fight.attack(" + ref(wolf) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(attack.done(), "attack has not finished");
            helper.assertTrue(!attack.succeeded() && attack.outcome().contains("refused by the owner"),
                    "the refusal does not come from asking the owner: " + attack.outcome());
            helper.assertTrue(wolf.getHealth() == wolf.getMaxHealth(), "the owner's wolf was hit");
            wolf.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 别人养的狼:主人也答应不了,硬规则拒绝,不弹卡;狼一滴血没掉。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void attack_someone_elses_tamed_wolf_is_forbidden_outright(GameTestHelper helper) {
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
        ToolRun attack = lua(companion, "numen.fight.attack(" + ref(wolf) + ")");
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            helper.assertTrue(attack.done(), "attack has not finished");
            helper.assertTrue(!attack.succeeded() && attack.outcome().contains("hard rule attack(other)"),
                    "the refusal does not quote the hard rule: " + attack.outcome());
            helper.assertTrue(!asked[0], "a hard rule raised a consent card");
            helper.assertTrue(wolf.getHealth() == wolf.getMaxHealth(), "the tamed wolf was hit");
            wolf.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 装着钻石的箱子带着方块实体:拆了东西会洒,要问;主人不在就不挖,箱子和五颗钻石都在。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
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

    /**
     * 拿水桶对着主人木板旁边的地面右键:倒水是危险品挨着主人的东西,要问;主人不在,问不到就不倒——
     * 走的是 numen.use.block 的放置路径,和 build 一样由权限层裁决。水没倒出来,桶还是满的。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void interact_at_water_next_to_the_owners_planks_needs_the_owner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer absentOwner = barePlayer(helper, "gametest_absent_owner");
        ownedBlocks(helper, absentOwner.getUUID(), List.of(new BlockPos(6, 2, 4)), Blocks.OAK_PLANKS);
        BlockPos floor = helper.absolutePos(new BlockPos(6, 1, 5));
        NumenPlayer companion = adopted(spawnAt(helper, "gametest_waterer", new BlockPos(4, 2, 5), false), absentOwner);
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
     * 主人用命令写 {@code deny break(owner)}:收下这一行。她 numen.use.hit 左键打主人的石头被拒、理由是那一行规则,石头还在;
     * 没主人的石头照常挖掉。从头到尾不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void a_deny_row_guards_what_is_the_owners(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ownersRel = new BlockPos(6, 2, 5);
        BlockPos naturalRel = new BlockPos(6, 2, 3);
        ServerPlayer owner = presentPlayer(helper, null, "gametest_house_owner");
        ownedBlocks(helper, owner.getUUID(), List.of(ownersRel), Blocks.STONE);
        level.setBlockAndUpdate(helper.absolutePos(naturalRel), Blocks.STONE.defaultBlockState());
        NumenPlayer companion = adopted(spawnAt(helper, "gametest_house_sitter", new BlockPos(4, 2, 5), true), owner);

        List<String> added = runAs(owner, "numen permission rules add deny break(owner)");
        helper.assertTrue(added.stream().anyMatch(m -> m.contains("Added to deny")), "the row was not added: " + added);

        ToolRun[] calls = new ToolRun[2];
        int[] step = {0};
        boolean[] asked = new boolean[1];
        helper.runAfterDelay(5, () -> {
            calls[0] = click(helper, companion, "left", ownersRel);
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
            helper.assertTrue(!calls[0].succeeded() && refused.contains("denied by rule break(owner)"),
                    "the dig of the owner's stone was not refused by the row: " + refused);
            helper.assertTrue(level.getBlockState(helper.absolutePos(ownersRel)).is(Blocks.STONE),
                    "the owner's stone was dug");
            helper.assertTrue(calls[1].succeeded()
                            && level.getBlockState(helper.absolutePos(naturalRel)).isAir(),
                    "the stone that is nobody's was not dug: " + calls[1].outcome());
            helper.assertTrue(!asked[0], "a deny row raised a consent card");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 主人不许挖他的:谷仓里主人的南瓜离她近,野地里长的远,一次扫描两个都找到。要一个南瓜,{@code arrive = "dig"} 只去挖得成
     * 的那个,她挖野地里那个,谷仓里的一动不动;对同样的方块再要一个,只剩谷仓里的,当场拒绝、理由是那一行规则。不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 231, batch = "numen_permission")
    public static void work_dig_leaves_the_denied_blocks_of_the_owner_standing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos insideRel = new BlockPos(5, 2, 5);
        BlockPos outsideRel = new BlockPos(11, 2, 5);
        ServerPlayer owner = presentPlayer(helper, null, "gametest_farmer");
        ownedBlocks(helper, owner.getUUID(), List.of(insideRel), Blocks.PUMPKIN);
        level.setBlockAndUpdate(helper.absolutePos(outsideRel), Blocks.PUMPKIN.defaultBlockState());
        NumenPlayer companion = adopted(spawnAt(helper, "gametest_harvester", new BlockPos(2, 2, 5), false), owner);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        storeOf(owner).add(com.dwinovo.numen.permission.Verdict.Kind.DENY,
                com.dwinovo.numen.api.permission.Groups.parse("break(owner)"));
        Mining first = mineScanned(helper, companion, 10, "minecraft:pumpkin", 1);
        Mining[] second = new Mining[1];
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            helper.assertTrue(first.done(), "the first mine has not finished");
            helper.assertTrue(first.succeeded() && level.getBlockState(helper.absolutePos(outsideRel)).isAir(),
                    "the wild pumpkin was not mined: " + first.outcome());
            helper.assertTrue(level.getBlockState(helper.absolutePos(insideRel)).is(Blocks.PUMPKIN),
                    "the pumpkin of the owner in the barn was mined");
            if (second[0] == null) {
                second[0] = mine(companion, first.blocks(), 1);
            }
            helper.assertTrue(second[0].done(), "the second mine has not finished");
            helper.assertTrue(!second[0].succeeded() && second[0].outcome().contains("break(owner)"),
                    "the second mine does not stop on the owner's row: " + second[0].outcome());
            helper.assertTrue(level.getBlockState(helper.absolutePos(insideRel)).is(Blocks.PUMPKIN),
                    "the pumpkin of the owner in the barn was mined");
            helper.assertTrue(!asked[0], "a deny row raised a consent card");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    // ---- 谁的:别人的方块、生成的结构 ----

    /**
     * 方块是谁的由回答者说:一个方块归属的回答者说这块石头是别的玩家的,她 {@code numen.work.dig} 挖它被硬规则拒了、不弹卡,石头还在;
     * 旁边没人说是谁的那一块照常挖掉。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void a_block_an_answerer_says_is_someone_elses_is_not_hers_to_dig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos theirs = helper.absolutePos(new BlockPos(6, 2, 5));
        BlockPos nobodys = helper.absolutePos(new BlockPos(6, 2, 3));
        level.setBlockAndUpdate(nobodys, Blocks.STONE.defaultBlockState());
        ownedBlocks(helper, UUID.randomUUID(), List.of(new BlockPos(6, 2, 5)), Blocks.STONE);
        NumenPlayer companion = spawnAt(helper, "gametest_neighbour", new BlockPos(4, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        ToolRun refused = lua(companion, "numen.work.dig(" + xyz(theirs) + ")");
        ToolRun[] dug = new ToolRun[1];
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> {
            asked[0] |= desk(companion).pending() != null;
            if (dug[0] == null && refused.done()) {
                dug[0] = lua(companion, "numen.work.dig(" + xyz(nobodys) + ")");
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(dug[0] != null && dug[0].done(), "the two digs have not finished");
            helper.assertTrue(!refused.succeeded() && refused.outcome().contains("hard rule break(other)"),
                    "the dig of someone else's stone was not refused by the hard rule: " + refused.outcome());
            helper.assertTrue(level.getBlockState(theirs).is(Blocks.STONE), "someone else's stone was dug");
            helper.assertTrue(dug[0].succeeded() && level.getBlockState(nobodys).isAir(),
                    "the stone that is nobody's was not dug: " + dug[0].outcome());
            helper.assertTrue(!asked[0], "a hard rule raised a consent card");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 生成的结构记着结构件范围:村庄件里的木板问(村庄是有人住的),废弃矿井的木板直接挖。
     * 结构起点直接写进区块(场景里造不出整座村庄),裁决读的是 StructureManager 同一份区块存档。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void village_planks_ask_and_mineshaft_planks_do_not(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos villageRel = new BlockPos(3, 2, 3);
        BlockPos shaftRel = new BlockPos(11, 2, 11);
        BlockPos village = helper.absolutePos(villageRel);
        BlockPos shaft = helper.absolutePos(shaftRel);
        level.setBlockAndUpdate(village, Blocks.OAK_PLANKS.defaultBlockState());
        level.setBlockAndUpdate(shaft, Blocks.OAK_PLANKS.defaultBlockState());
        var registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var placedStarts = new java.util.ArrayList<Runnable>();
        for (var entry : List.of(
                java.util.Map.entry(net.minecraft.world.level.levelgen.structure.BuiltinStructures.VILLAGE_PLAINS, village),
                java.util.Map.entry(net.minecraft.world.level.levelgen.structure.BuiltinStructures.MINESHAFT, shaft))) {
            net.minecraft.world.level.levelgen.structure.Structure structure = registry.getOrThrow(entry.getKey());
            BlockPos at = entry.getValue();
            var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(at.getX() - 2, at.getY() - 2,
                    at.getZ() - 2, at.getX() + 2, at.getY() + 4, at.getZ() + 2);
            var piece = new net.minecraft.world.level.levelgen.structure.StructurePiece(
                    net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType.MINE_SHAFT_ROOM, 0, box) {
                @Override
                protected void addAdditionalSaveData(
                        net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext context,
                        net.minecraft.nbt.CompoundTag tag) {
                }

                @Override
                public void postProcess(net.minecraft.world.level.WorldGenLevel world,
                                        net.minecraft.world.level.StructureManager manager,
                                        net.minecraft.world.level.chunk.ChunkGenerator generator,
                                        net.minecraft.util.RandomSource random,
                                        net.minecraft.world.level.levelgen.structure.BoundingBox within,
                                        net.minecraft.world.level.ChunkPos chunkPos, BlockPos pos) {
                }
            };
            var chunk = level.getChunk(at);
            var start = new net.minecraft.world.level.levelgen.structure.StructureStart(structure, chunk.getPos(), 0,
                    new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(List.of(piece)));
            var before = java.util.Map.copyOf(chunk.getAllStarts());
            var withOurs = new java.util.HashMap<>(before);
            withOurs.put(structure, start);
            chunk.setAllStarts(withOurs);
            placedStarts.add(() -> chunk.setAllStarts(before));
        }
        NumenPlayer companion = spawnAt(helper, "gametest_surveyor2", new BlockPos(7, 2, 7), false);
        var gate = com.dwinovo.numen.api.permission.Permission.gateFor(companion);
        var inVillage = gate.judgeLive(com.dwinovo.numen.api.permission.Action.breakBlock(village,
                level.getBlockState(village)), level);
        var inShaft = gate.judgeLive(com.dwinovo.numen.api.permission.Action.breakBlock(shaft,
                level.getBlockState(shaft)), level);
        // 倒着还原:两处落在同一个区块时,后拍的"原状"里已经有先放的那一个,顺着还原会把它留下
        java.util.Collections.reverse(placedStarts);
        placedStarts.forEach(Runnable::run);
        CompanionFactory.despawn(level.getServer(), companion);

        helper.assertTrue(inVillage.asks() && inVillage.rule().toString().equals("break(structure(#minecraft:village))"),
                "planks in a village: " + inVillage);
        helper.assertTrue(inShaft.allowed() && inShaft.rule().toString()
                        .equals("break(nature & !block_entity & !structure(#minecraft:village))"),
                "planks in an abandoned mineshaft: " + inShaft);
        helper.succeed();
    }

    // ---- 命中账、点名一只同伴、她改规则 ----

    /**
     * 规则列表:主人层每行带序号、谁写的、命中几次,出厂层与硬规则也各有账。真动手的才记:主人写了不许挖泥土,她挖泥土被那一行拒了,
     * 又挖了一块石头(出厂放行那一行),列表里这两行的次数跟存档里的账一致,没用到的行写"从没命中过"。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void the_rule_list_shows_how_often_each_row_fired(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos dirtRel = new BlockPos(6, 2, 4);
        BlockPos stoneRel = new BlockPos(6, 2, 6);
        level.setBlockAndUpdate(helper.absolutePos(dirtRel), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(stoneRel), Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_tallier", new BlockPos(4, 2, 5), true);
        ServerPlayer owner = presentOwner(helper, companion, "gametest_bookkeeper");
        runAs(owner, "numen permission rules add deny break(minecraft:dirt)");
        ToolRun[] calls = new ToolRun[2];
        int[] step = {0};
        helper.runAfterDelay(5, () -> {
            calls[0] = click(helper, companion, "left", dirtRel);
            step[0] = 1;
        });
        helper.onEachTick(() -> {
            if (step[0] == 1 && calls[0].done()) {
                calls[1] = click(helper, companion, "left", stoneRel);
                step[0] = 2;
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(step[0] == 2 && calls[1].done(), "the two clicks have not finished");
            helper.assertTrue(!calls[0].succeeded() && calls[1].succeeded(),
                    "the dirt was dug or the stone was not: " + calls[0].outcome() + " / " + calls[1].outcome());
            var store = storeOf(owner);
            var denyRule = com.dwinovo.numen.api.permission.Groups.parse("break(minecraft:dirt)");
            var factoryRule = com.dwinovo.numen.api.permission.Groups.parse(
                    "break(nature & !block_entity & !structure(#minecraft:village))");
            int denied = store.hitsOf(com.dwinovo.numen.api.permission.Layers.OWNER,
                    com.dwinovo.numen.permission.Verdict.Kind.DENY, denyRule).count();
            int allowed = store.hitsOf(com.dwinovo.numen.api.permission.Layers.FACTORY,
                    com.dwinovo.numen.permission.Verdict.Kind.ALLOW, factoryRule).count();
            helper.assertTrue(denied >= 1 && allowed >= 1, "the ledger missed the live questions: " + denied + "/" + allowed);
            List<String> lines = String.join("\n", runAs(owner, "numen permission rules list")).lines().toList();
            helper.assertTrue(lines.stream().anyMatch(l -> l.contains("1. break(minecraft:dirt)")
                            && l.contains(denied + " hit(s)")),
                    "the list does not show the owner's row with its hits: " + lines);
            helper.assertTrue(lines.stream().anyMatch(
                            l -> l.contains("- break(nature & !block_entity & !structure(#minecraft:village))")
                            && l.contains(allowed + " hit(s)")),
                    "the list does not show the factory row with its hits: " + lines);
            helper.assertTrue(lines.stream().anyMatch(l -> l.contains("- deny break(other)") && l.contains("never hit")),
                    "the list does not show an unused hard rule as never hit: " + lines);
            helper.assertTrue(lines.stream().anyMatch(l -> l.contains("Hard rules")), "the list lacks the hard rules");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 她改规则:收紧(deny、ask)当场写进主人那一层、回执说清、主人在场就当场告诉他,写的人记成她、带着她说的理由;写错的规则回教学式
     * 的错误。放宽(allow)与删行发一张征询卡——卡上就是她没法自己跑的那条命令、不能"允许并记住",主人拒绝了不写,允许了才写(来源记成她)。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void she_tightens_at_once_and_loosening_goes_through_a_card(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_scribe", new BlockPos(4, 2, 4), false);
        ServerPlayer owner = presentOwner(helper, companion, "gametest_scribe_owner");
        var deny = lua(companion, "return numen.permission.deny(\"break(owner & minecraft:chest)\", "
                + "{why = \"you said to leave the chests\"})");
        var again = lua(companion, "return numen.permission.deny(\"break(owner & minecraft:chest)\")");
        var typo = lua(companion, "numen.permission.deny(\"brek(owner)\")");
        var ask = lua(companion, "return numen.permission.ask(\"break(nature & minecraft:oak_log)\")");
        ToolRun[] later = new ToolRun[3];
        int[] step = {0};
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            switch (step[0]) {
                case 0 -> {
                    if (deny.done() && again.done() && typo.done() && ask.done()) {
                        later[0] = lua(companion, "return numen.permission.allow(\"break(owner & minecraft:wheat)\", "
                                + "{why = \"harvest the farm\"})");
                        step[0] = 1;
                    }
                }
                case 1 -> {
                    if (pending != null) {
                        var item = pending.items().get(0);
                        helper.assertTrue(item.name().getString().equals(
                                        "/numen permission rules add allow break(owner & minecraft:wheat)")
                                        && item.remember() == null,
                                "the card is not the command she cannot run herself, or can be remembered: " + item);
                        helper.assertTrue(storeOf(owner).rules().allow().isEmpty(), "written before the owner answered");
                        desk(companion).answer(pending.id(),
                                com.dwinovo.numen.api.permission.ConsentAnswer.Decision.DENY, "还不行");
                        step[0] = 2;
                    }
                }
                case 2 -> {
                    if (later[0].done()) {
                        later[1] = lua(companion, "return numen.permission.allow(\"break(owner & minecraft:wheat)\", "
                                + "{why = \"harvest the farm\"})");
                        step[0] = 3;
                    }
                }
                case 3 -> {
                    if (pending != null) {
                        desk(companion).answer(pending.id(),
                                com.dwinovo.numen.api.permission.ConsentAnswer.Decision.ALLOW_REMEMBER, "");
                        step[0] = 4;
                    }
                }
                case 4 -> {
                    if (later[1].done()) {
                        later[2] = lua(companion, "return numen.permission.remove(\"deny break(owner & minecraft:chest)\")");
                        step[0] = 5;
                    }
                }
                case 5 -> {
                    if (pending != null) {
                        helper.assertTrue(pending.items().get(0).name().getString().startsWith(
                                        "/numen permission rules remove deny 1")
                                        && pending.items().get(0).name().getString().contains("break(owner & minecraft:chest)"),
                                "the removal card: " + pending.items());
                        desk(companion).answer(pending.id(),
                                com.dwinovo.numen.api.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
                        step[0] = 6;
                    }
                }
                default -> { }
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(step[0] == 6 && later[2].done(), "the card round trips have not finished");
            helper.assertTrue(deny.succeeded() && Boolean.TRUE.equals(deny.field("changed"))
                            && Boolean.TRUE.equals(deny.field("told"))
                            && "deny break(owner & minecraft:chest)".equals(deny.field("row")),
                    "the deny did not report what it wrote and that the owner was told: " + deny.reply());
            helper.assertTrue(again.succeeded() && Boolean.FALSE.equals(again.field("changed")),
                    "a row already there was reported as new: " + again.reply());
            helper.assertTrue(typo.refused() && typo.outcome().contains("unknown verb 'brek'"),
                    "a mistyped rule was not taught: " + typo.outcome());
            helper.assertTrue(ask.succeeded(), "ask failed: " + ask.outcome());
            helper.assertTrue(!later[0].succeeded() && later[0].outcome().contains("还不行"),
                    "a refused allow did not quote the owner: " + later[0].outcome());
            helper.assertTrue(later[1].succeeded() && later[2].succeeded(), "an allowed card did not go through: "
                    + later[1].outcome() + " / " + later[2].outcome());
            var store = storeOf(owner);
            helper.assertTrue(store.rows(com.dwinovo.numen.permission.Verdict.Kind.ALLOW).size() == 1
                            && store.rows(com.dwinovo.numen.permission.Verdict.Kind.ALLOW).get(0).source()
                            == com.dwinovo.numen.api.permission.PermissionStore.Source.HER
                            && store.rows(com.dwinovo.numen.permission.Verdict.Kind.ALLOW).get(0).note().equals("harvest the farm"),
                    "the allow row is not hers, with her reason, and alone: " + store.rows(
                            com.dwinovo.numen.permission.Verdict.Kind.ALLOW));
            helper.assertTrue(store.rows(com.dwinovo.numen.permission.Verdict.Kind.DENY).isEmpty(),
                    "the removed row is still there");
            helper.assertTrue(store.rows(com.dwinovo.numen.permission.Verdict.Kind.ASK).size() == 1
                            && store.rows(com.dwinovo.numen.permission.Verdict.Kind.ASK).get(0).source()
                            == com.dwinovo.numen.api.permission.PermissionStore.Source.HER,
                    "the ask row she added is missing");
            String listed = String.join("\n", runAs(owner, "numen permission rules list"));
            helper.assertTrue(listed.contains("she added it: harvest the farm"),
                    "the owner's list does not mark her rows: " + listed);
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }
}
