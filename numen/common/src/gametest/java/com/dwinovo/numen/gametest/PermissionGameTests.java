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
 * 权限层:主人的东西要问、别人的东西不许、主人给每种动作定的档位与"不用问"的名单、记住的同意、右键与拿东西、原生通道上的退回。原版里方块没有主人,用例里"主人的""别人的"
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
     * 交给她的那一团被拒就停:主人把"挖方块"定成不许;扫描只说找到了什么,不说主人许不许。mine 那一团,派发当场按拒绝收场、
     * 理由是主人的这一档,菌柄一根不少,也不弹卡。
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
        storeOf(owner).edit(companion.getUUID(), new com.dwinovo.numen.permission.Edit.SetTier(
                com.dwinovo.numen.permission.Verb.BREAK, com.dwinovo.numen.permission.Tier.DENY));
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
            helper.assertTrue(!mine[0].succeeded() && result.contains("setting for break is deny"),
                    "the refusal does not carry the owner's setting: " + result);
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
     * 等主人点头的时候那只动物挪了窝,征询还是原来那一张:点名打一头起了名字的猪(主人把"打实体"定成问),征询挂上后每隔几刻把它挪一格,
     * 号始终不变——实体认的是那一只,不是它脚下的格,挪一步不是新的请求。主人拒绝后猪还活着。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void a_target_that_moves_keeps_its_consent_request(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = armedCompanion(helper, new BlockPos(4, 2, 4));
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_swineherd");
        storeOf(owner).edit(companion.getUUID(), new com.dwinovo.numen.permission.Edit.SetTier(
                com.dwinovo.numen.permission.Verb.ATTACK, com.dwinovo.numen.permission.Tier.ASK));
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
     * "以后都允许"答复——两次都丢了、回执都交代主人允许了(第一次带着附言原话,记住的那次还交代记下了哪一项);记住之后第三次丢绿宝石
     * 不再问,名单里多了一项。别人敲命令答不了;答一个没挂着的号说清楚没有。
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
                        step[0] = 1;
                    }
                }
                case 1 -> {
                    if (calls[0].done()) {
                        helper.assertTrue(calls[0].succeeded()
                                        && calls[0].outcome().contains("the owner allowed")
                                        && calls[0].outcome().contains("小心点"),
                                "the card answer did not go through, or lost the note: " + calls[0].outcome());
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
            helper.assertTrue(storeOf(owner).settingsOf(companion.getUUID()).names(
                            com.dwinovo.numen.permission.Verb.DROP).equals(List.of("minecraft:emerald")),
                    "the remembered name: " + storeOf(owner).settingsOf(companion.getUUID()).entries());
            List<String> stale = runAs(owner, "numen consent deny 987654321");
            helper.assertTrue(stale.stream().anyMatch(m -> m.contains("No pending consent request")),
                    "answering a missing request did not say so: " + stale);
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
            leave(stranger);
        });
    }

    /**
     * 点"以后都允许"记下的名字由动作定:挖箱子记那种方块;打一只野生的、起了名字的狼记它这一种(实体类型),打一只驯服了的狼记它这一只。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void remembering_names_the_one_or_the_kind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = helper.absolutePos(new BlockPos(6, 2, 5));
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        var wild = EntityType.WOLF.create(level);
        var pet = EntityType.WOLF.create(level);
        helper.assertTrue(wild != null && pet != null, "wolf did not spawn");
        BlockPos wolfAt = helper.absolutePos(new BlockPos(6, 2, 9));
        wild.moveTo(wolfAt.getX() + 0.5, wolfAt.getY(), wolfAt.getZ() + 0.5, 0.0f, 0.0f);
        wild.setNoAi(true);
        wild.setCustomName(net.minecraft.network.chat.Component.literal("Rex"));
        level.addFreshEntity(wild);
        pet.moveTo(wolfAt.getX() + 2.5, wolfAt.getY(), wolfAt.getZ() + 0.5, 0.0f, 0.0f);
        pet.setNoAi(true);
        pet.setOwnerUUID(UUID.randomUUID());
        level.addFreshEntity(pet);
        NumenPlayer companion = spawnAt(helper, "gametest_scribe", new BlockPos(3, 2, 5), false);

        var gate = com.dwinovo.numen.api.permission.Permission.gateFor(companion);
        var digChest = com.dwinovo.numen.api.permission.Action.breakBlock(chest, level.getBlockState(chest));
        var chestItem = gate.consentItem(digChest, gate.judgeLive(digChest, level));
        helper.assertTrue(new com.dwinovo.numen.permission.Settings.Entry(com.dwinovo.numen.permission.Verb.BREAK,
                        "minecraft:chest").equals(chestItem.remembering()), "remembered " + chestItem.remembering());

        var hitWild = com.dwinovo.numen.api.permission.Action.attack(wild);
        var wildItem = gate.consentItem(hitWild, gate.judgeLive(hitWild, level));
        helper.assertTrue(new com.dwinovo.numen.permission.Settings.Entry(com.dwinovo.numen.permission.Verb.ATTACK,
                        "minecraft:wolf").equals(wildItem.remembering()),
                "a wild wolf is remembered as a kind: " + wildItem.remembering());

        var hitPet = com.dwinovo.numen.api.permission.Action.attack(pet);
        var petItem = gate.consentItem(hitPet, gate.judgeLive(hitPet, level));
        helper.assertTrue(new com.dwinovo.numen.permission.Settings.Entry(com.dwinovo.numen.permission.Verb.ATTACK,
                        pet.getUUID().toString()).equals(petItem.remembering()),
                "a tamed wolf is remembered as this one: " + petItem.remembering());

        wild.discard();
        pet.discard();
        CompanionFactory.despawn(level.getServer(), companion);
        helper.succeed();
    }

    /**
     * 只有玩家做主人才算有主:主人是一只活着的唤魔者(它的随从)的狼,没有人的主人,按野生的放行;
     * 主人找不到(离线的玩家)的狼仍是别人的,不许。
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
     * 主人把"交互方块"定成问之后,右键打开装着东西的箱子要问(出厂是放行的):numen.container.take
     * 悬着、箱子没打开、钻石还在里面;主人允许后箱子打开,往外拿不再另问,钻石进了她的背包。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void an_ask_tier_on_use_block_asks_before_opening_a_full_container(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = chestWithDiamonds(helper, new BlockPos(6, 2, 5), 3);
        NumenPlayer companion = spawnAt(helper, "gametest_borrower", new BlockPos(4, 2, 5), false);
        ServerPlayer owner = presentPlayer(helper, companion, "gametest_lender");
        runAs(owner, "numen permission set gametest_borrower use_block ask");
        ToolRun take = lua(companion, "numen.container.take(" + xyz(chest) + ", \"minecraft:diamond\")");
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            if (pending != null && !asked[0]) {
                helper.assertTrue(pending.items().size() == 1 && pending.items().get(0).kind() == com.dwinovo.numen.permission.Verb.USE_BLOCK
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

    // ---- 把档位定成问、打别人的狼 ----

    /** 主人把"打实体"定成问:打村民前征询只挂一次,允许之后才动手,村民挨了打。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void attack_asks_when_the_tier_is_ask_then_hits_after_yes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var villager = EntityType.VILLAGER.create(level);
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 4));
        villager.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        villager.setNoAi(true);
        level.addFreshEntity(villager);
        NumenPlayer companion = spawnAt(helper, "gametest_enforcer", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_SWORD));
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_magistrate");
        storeOf(owner).edit(companion.getUUID(), new com.dwinovo.numen.permission.Edit.SetTier(
                com.dwinovo.numen.permission.Verb.ATTACK, com.dwinovo.numen.permission.Tier.ASK));
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

    /** 别人养的狼:回答"归属"的说是别人的,不许,主人也答应不了,不弹卡;狼一滴血没掉。 */
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
            helper.assertTrue(!attack.succeeded() && attack.outcome().contains("belongs to someone else"),
                    "the refusal does not say whose it is: " + attack.outcome());
            helper.assertTrue(!asked[0], "a refusal raised a consent card");
            helper.assertTrue(wolf.getHealth() == wolf.getMaxHealth(), "the tamed wolf was hit");
            wolf.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 主人用命令把"挖方块"定成不许:收下。她 numen.use.hit 左键打石头被拒、理由是主人的这一档,石头还在;主人再把它定回许,同一块石头
     * 就挖掉了。从头到尾不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void a_deny_tier_refuses_the_verb_until_it_is_set_back(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stoneRel = new BlockPos(6, 2, 5);
        level.setBlockAndUpdate(helper.absolutePos(stoneRel), Blocks.STONE.defaultBlockState());
        ServerPlayer owner = presentPlayer(helper, null, "gametest_house_owner");
        NumenPlayer companion = adopted(spawnAt(helper, "gametest_house_sitter", new BlockPos(4, 2, 5), true), owner);

        List<String> set = runAs(owner, "numen permission set gametest_house_sitter break deny");
        helper.assertTrue(set.stream().anyMatch(m -> m.contains("break is now deny")), "the tier was not set: " + set);

        ToolRun[] calls = new ToolRun[2];
        int[] step = {0};
        boolean[] asked = new boolean[1];
        helper.runAfterDelay(5, () -> {
            calls[0] = click(helper, companion, "left", stoneRel);
            step[0] = 1;
        });
        helper.onEachTick(() -> {
            asked[0] |= desk(companion).pending() != null;
            if (step[0] == 1 && calls[0].done()) {
                List<String> back = runAs(owner, "numen permission set gametest_house_sitter break allow");
                helper.assertTrue(back.stream().anyMatch(m -> m.contains("break is now allow")),
                        "the tier was not set back: " + back);
                calls[1] = click(helper, companion, "left", stoneRel);
                step[0] = 2;
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(step[0] == 2 && calls[1].done(), "the two clicks have not finished");
            helper.assertTrue(!calls[0].succeeded() && calls[0].outcome().contains("setting for break is deny"),
                    "the dig was not refused by the tier: " + calls[0].outcome());
            helper.assertTrue(calls[1].succeeded() && level.getBlockState(helper.absolutePos(stoneRel)).isAir(),
                    "the stone was not dug once the tier was allow again: " + calls[1].outcome());
            helper.assertTrue(!asked[0], "a deny tier raised a consent card");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 别人的挖不得:谷仓里别人的南瓜离她近,野地里长的远,一次扫描两个都找到。要一个南瓜,{@code arrive = "dig"} 只去挖得成
     * 的那个,她挖野地里那个,谷仓里的一动不动;对同样的方块再要一个,只剩谷仓里的,当场拒绝、理由是它是别人的。不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 231, batch = "numen_permission")
    public static void work_dig_leaves_someone_elses_blocks_standing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos insideRel = new BlockPos(5, 2, 5);
        BlockPos outsideRel = new BlockPos(11, 2, 5);
        ServerPlayer owner = presentPlayer(helper, null, "gametest_farmer");
        ownedBlocks(helper, UUID.randomUUID(), List.of(insideRel), Blocks.PUMPKIN);
        level.setBlockAndUpdate(helper.absolutePos(outsideRel), Blocks.PUMPKIN.defaultBlockState());
        NumenPlayer companion = adopted(spawnAt(helper, "gametest_harvester", new BlockPos(2, 2, 5), false), owner);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
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
            helper.assertTrue(!second[0].succeeded() && second[0].outcome().contains("belongs to someone else"),
                    "the second mine does not stop on whose it is: " + second[0].outcome());
            helper.assertTrue(level.getBlockState(helper.absolutePos(insideRel)).is(Blocks.PUMPKIN),
                    "the pumpkin of the owner in the barn was mined");
            helper.assertTrue(!asked[0], "a deny row raised a consent card");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    // ---- 谁的:别人的方块 ----

    /**
     * 方块是谁的由回答者说:一个方块归属的回答者说这块石头是别的玩家的,她 {@code numen.work.dig} 挖它被拒了、不弹卡,石头还在;
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
            helper.assertTrue(!refused.succeeded() && refused.outcome().contains("belongs to someone else"),
                    "the dig of someone else's stone was not refused: " + refused.outcome());
            helper.assertTrue(level.getBlockState(theirs).is(Blocks.STONE), "someone else's stone was dug");
            helper.assertTrue(dug[0].succeeded() && level.getBlockState(nobodys).isAir(),
                    "the stone that is nobody's was not dug: " + dug[0].outcome());
            helper.assertTrue(!asked[0], "a refusal raised a consent card");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ---- 她改设置、默认问的动作、以后都允许 ----

    /**
     * 她改自己的权限设置:往严了调(把"挖方块"定成问、从名单里删一项)当场生效、回执说清、主人在场就当场告诉他;已经如愿的说没变;
     * 写错的动作回教学式的错误。调松(把"扔东西"定成许、往名单里加一项)发一张征询卡——卡上就是她没法自己跑的那条命令、不能"以后都允许",
     * 主人拒绝了不改,允许了才改。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void she_tightens_at_once_and_loosening_goes_through_a_card(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_scribe", new BlockPos(4, 2, 4), false);
        ServerPlayer owner = presentOwner(helper, companion, "gametest_scribe_owner");
        var tighten = lua(companion, "return numen.permission.set({verb = \"break\", tier = \"ask\"})");
        var again = lua(companion, "return numen.permission.set({verb = \"break\", tier = \"ask\"})");
        var typo = lua(companion, "numen.permission.set({verb = \"brek\", tier = \"ask\"})");
        ToolRun[] later = new ToolRun[4];
        int[] step = {0};
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            switch (step[0]) {
                case 0 -> {
                    if (tighten.done() && again.done() && typo.done()) {
                        later[0] = lua(companion, "return numen.permission.set({verb = \"drop\", tier = \"allow\"})");
                        step[0] = 1;
                    }
                }
                case 1 -> {
                    if (pending != null) {
                        var item = pending.items().get(0);
                        helper.assertTrue(item.name().getString().equals(
                                        "/numen permission set gametest_scribe drop allow") && item.remembering() == null,
                                "the card is not the command she cannot run herself, or can be remembered: " + item);
                        helper.assertTrue(storeOf(owner).settingsOf(companion.getUUID()).tier(
                                        com.dwinovo.numen.permission.Verb.DROP) == com.dwinovo.numen.permission.Tier.ASK,
                                "changed before the owner answered");
                        desk(companion).answer(pending.id(),
                                com.dwinovo.numen.api.permission.ConsentAnswer.Decision.DENY, "还不行");
                        step[0] = 2;
                    }
                }
                case 2 -> {
                    if (later[0].done()) {
                        later[1] = lua(companion, "return numen.permission.set({verb = \"drop\", tier = \"allow\"})");
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
                        later[2] = lua(companion, "return numen.permission.add({verb = \"break\", name = \"minecraft:wheat\"})");
                        step[0] = 5;
                    }
                }
                case 5 -> {
                    if (pending != null) {
                        helper.assertTrue(pending.items().get(0).name().getString().equals(
                                        "/numen permission list add gametest_scribe break minecraft:wheat"),
                                "the list card: " + pending.items());
                        desk(companion).answer(pending.id(),
                                com.dwinovo.numen.api.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
                        step[0] = 6;
                    }
                }
                case 6 -> {
                    if (later[2].done()) {
                        later[3] = lua(companion, "return numen.permission.remove({verb = \"break\", name = \"minecraft:wheat\"})");
                        step[0] = 7;
                    }
                }
                default -> { }
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(step[0] == 7 && later[3].done(), "the card round trips have not finished");
            helper.assertTrue(tighten.succeeded() && Boolean.TRUE.equals(tighten.field("changed"))
                            && Boolean.TRUE.equals(tighten.field("told")) && "break ask".equals(tighten.field("now")),
                    "the tightening did not report what it did and that the owner was told: " + tighten.reply());
            helper.assertTrue(again.succeeded() && Boolean.FALSE.equals(again.field("changed")),
                    "a tier already there was reported as new: " + again.reply());
            helper.assertTrue(typo.refused() && typo.outcome().contains("'brek' is not a verb"),
                    "a mistyped verb was not taught: " + typo.outcome());
            helper.assertTrue(!later[0].succeeded() && later[0].outcome().contains("还不行"),
                    "a refused loosening did not quote the owner: " + later[0].outcome());
            helper.assertTrue(later[1].succeeded() && later[2].succeeded(), "an allowed card did not go through: "
                    + later[1].outcome() + " / " + later[2].outcome());
            helper.assertTrue(later[3].succeeded() && Boolean.TRUE.equals(later[3].field("changed")),
                    "taking a name off the list was not done at once: " + later[3].outcome());
            var settings = storeOf(owner).settingsOf(companion.getUUID());
            helper.assertTrue(settings.tier(com.dwinovo.numen.permission.Verb.BREAK) == com.dwinovo.numen.permission.Tier.ASK
                            && settings.tier(com.dwinovo.numen.permission.Verb.DROP) == com.dwinovo.numen.permission.Tier.ALLOW
                            && settings.names(com.dwinovo.numen.permission.Verb.BREAK).isEmpty(),
                    "the settings are not what the answers left: " + settings);
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 出厂里"扔东西"是问:她丢钻石先挂一张卡,东西一件没丢;主人在卡上允许,才丢出去。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void a_verb_asked_by_default_puts_up_a_card_and_goes_after_yes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_giver", new BlockPos(4, 2, 4), false);
        ServerPlayer owner = presentOwner(helper, companion, "gametest_giver_owner");
        companion.getInventory().add(new ItemStack(Items.DIAMOND, 2));
        ToolRun drop = lua(companion, "numen.inv.drop(\"minecraft:diamond\", {count = 2})");
        boolean[] answered = new boolean[1];

        succeedWhen(helper, () -> {
            var pending = desk(companion).pending();
            if (!answered[0]) {
                helper.assertTrue(pending != null, "no card for a verb that is asked by default");
                helper.assertTrue(pending.items().get(0).kind() == com.dwinovo.numen.permission.Verb.DROP,
                        "the card is not about dropping: " + pending.items());
                helper.assertTrue(!drop.done() && companion.getInventory().countItem(Items.DIAMOND) == 2,
                        "dropped before the owner said yes");
                answered[0] = desk(companion).answer(pending.id(),
                        com.dwinovo.numen.api.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
            }
            helper.assertTrue(drop.done() && drop.succeeded(), "the drop has not finished after yes: " + drop.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 0, "the diamonds were not dropped");
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * "以后都允许"把名字记进这个动作的名单:点完之后名单里多了那一样东西,同一只同伴下一次再丢它不再问;丢另一样还是问。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void always_allow_puts_the_name_on_the_list_and_it_is_not_asked_again(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_regular", new BlockPos(4, 2, 4), false);
        ServerPlayer owner = presentOwner(helper, companion, "gametest_regular_owner");
        companion.getInventory().add(new ItemStack(Items.DIAMOND, 2));
        companion.getInventory().add(new ItemStack(Items.EMERALD, 2));
        ToolRun[] calls = new ToolRun[3];
        int[] step = {0};
        calls[0] = lua(companion, "numen.inv.drop(\"minecraft:diamond\", {count = 1})");
        boolean[] askedForTheSecond = new boolean[1];
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            switch (step[0]) {
                case 0 -> {
                    if (pending != null) {
                        desk(companion).answer(pending.id(),
                                com.dwinovo.numen.api.permission.ConsentAnswer.Decision.ALLOW_REMEMBER, "");
                        step[0] = 1;
                    }
                }
                case 1 -> {
                    if (calls[0].done()) {
                        helper.assertTrue(storeOf(owner).settingsOf(companion.getUUID()).listed(
                                        com.dwinovo.numen.permission.Verb.DROP, "minecraft:diamond"),
                                "the name is not on the list: " + storeOf(owner).settingsOf(companion.getUUID()).entries());
                        calls[1] = lua(companion, "numen.inv.drop(\"minecraft:diamond\", {count = 1})");
                        step[0] = 2;
                    }
                }
                case 2 -> {
                    askedForTheSecond[0] |= pending != null;
                    if (calls[1].done()) {
                        calls[2] = lua(companion, "numen.inv.drop(\"minecraft:emerald\", {count = 1})");
                        step[0] = 3;
                    }
                }
                case 3 -> {
                    if (pending != null) {
                        desk(companion).answer(pending.id(),
                                com.dwinovo.numen.api.permission.ConsentAnswer.Decision.DENY, "");
                        step[0] = 4;
                    }
                }
                default -> { }
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(step[0] == 4 && calls[2].done(), "the three drops have not finished");
            helper.assertTrue(!askedForTheSecond[0], "asked again about a name that is on the list");
            helper.assertTrue(calls[1].succeeded() && companion.getInventory().countItem(Items.DIAMOND) == 0,
                    "the listed drop did not go: " + calls[1].outcome());
            helper.assertTrue(!calls[2].succeeded() && companion.getInventory().countItem(Items.EMERALD) == 2,
                    "something else on the same verb was dropped without asking: " + calls[2].outcome());
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }

    /**
     * 别人的宠物写在名单里也打不了:主人把那只狼记进"打实体"的名单,回答"归属"的说它是别人的,不许排在名单前面——不弹卡,狼一滴血没掉。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission")
    public static void someone_elses_pet_on_the_list_is_still_refused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var wolf = EntityType.WOLF.create(level);
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 4));
        wolf.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        wolf.setTame(true, false);
        wolf.setOwnerUUID(UUID.randomUUID());
        wolf.setNoAi(true);
        level.addFreshEntity(wolf);
        NumenPlayer companion = spawnAt(helper, "gametest_listed_hunter", new BlockPos(3, 2, 4), false);
        ServerPlayer owner = presentOwner(helper, companion, "gametest_listed_owner");
        storeOf(owner).edit(companion.getUUID(), new com.dwinovo.numen.permission.Edit.Add(
                com.dwinovo.numen.permission.Verb.ATTACK, wolf.getUUID().toString()));
        companion.getInventory().add(new ItemStack(Items.IRON_SWORD));
        ToolRun attack = lua(companion, "numen.fight.attack(" + ref(wolf) + ")");
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        succeedWhen(helper, () -> {
            helper.assertTrue(attack.done(), "attack has not finished");
            helper.assertTrue(!attack.succeeded() && attack.outcome().contains("belongs to someone else"),
                    "the refusal does not say whose it is: " + attack.outcome());
            helper.assertTrue(!asked[0], "a refusal raised a consent card");
            helper.assertTrue(wolf.getHealth() == wolf.getMaxHealth(), "the wolf was hit");
            wolf.discard();
            CompanionFactory.despawn(level.getServer(), companion);
            leave(owner);
        });
    }
}
