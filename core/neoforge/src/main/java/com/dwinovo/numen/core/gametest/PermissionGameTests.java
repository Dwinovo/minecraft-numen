package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.tools.BlockActionOps;
import com.dwinovo.numen.core.tools.MovementOps;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
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

    /**
     * 让主人"在场":另起一具身体进玩家列表当主人。登记处只认主人在不在线——不在就当场按拒绝,
     * 答不答复就无从测起。答复由用例直接调登记处,等于主人在卡片上按了键。
     */
    private static NumenPlayer presentOwner(GameTestHelper helper, NumenPlayer companion, String name) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(0, 2, 0));
        NumenPlayer owner = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(), name, UUID.randomUUID(),
                level, new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        companion.setOwnerUuid(owner.getUUID());
        return owner;
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

    /** goto 的 spec:连需要主人同意的格也算进路线。 */
    private static com.google.gson.JsonObject anySpec() {
        com.google.gson.JsonObject spec = new com.google.gson.JsonObject();
        spec.addProperty("alter", "any");
        return spec;
    }

    /**
     * 规格没说能动主人的东西就不动,也不问:主人的屋子,goto alter=natural。自然改动没有路,
     * 探针连要同意的格也算进去再查一次,回执是候选清单、标着 needing consent;墙一块不少,她还在屋里,
     * 没有弹过一张卡——征询只在选了这种路线、开走之前发生。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void goto_natural_lists_consent_routes_without_asking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ownersRoom(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_lodger", new BlockPos(7, 2, 7), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_landlord");
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        TaskRecord record = (TaskRecord) new MovementOps().moveTo(
                (double) target.getX(), (double) target.getY(), (double) target.getZ(), null, naturalSpec(), null,
                TaskDispatch.ctx("gametest-lodger", companion));
        TaskDispatch.runSync(companion, record, r -> {});
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        helper.succeedWhen(() -> {
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "goto has not finished");
            helper.assertTrue(!record.getResult().success(), "goto through the owner's wall must not succeed");
            helper.assertTrue(reply.contains("needing consent") && firstRouteId(reply) != null,
                    "the refusal does not list consent routes: " + reply);
            helper.assertTrue(!asked[0], "a natural goto must not ask the owner");
            helper.assertTrue(plankCount(helper, 7, 7) == planksBefore, "the owner's wall was damaged");
            helper.assertTrue(companion.blockPosition().distSqr(target) > 3 * 3,
                    "companion got out through the owner's wall?!");
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
        });
    }

    /**
     * 穿主人墙的路线开走前先问:goto alter=any,规划出的路要挖主人的墙,于是扣住不走、挂一条征询;
     * 等答复期间她一步不动、墙一块不少。主人允许后她拆墙出去到达目标,回执说主人允许过。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void goto_through_owners_wall_asks_then_walks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ownersRoom(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_tenant", new BlockPos(7, 2, 7), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_host");
        BlockPos start = companion.blockPosition();
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        TaskRecord record = (TaskRecord) new MovementOps().moveTo(
                (double) target.getX(), (double) target.getY(), (double) target.getZ(), null, anySpec(), null,
                TaskDispatch.ctx("gametest-tenant", companion));
        TaskDispatch.runSync(companion, record, r -> {});
        boolean[] answered = new boolean[1];

        helper.succeedWhen(() -> {
            if (!answered[0]) {
                var pending = desk(companion).pending();
                helper.assertTrue(pending != null, "no consent request before walking through the wall");
                helper.assertTrue(record.getResult() == null, "goto finished while waiting for the owner");
                helper.assertTrue(plankCount(helper, 7, 7) == planksBefore, "a plank broke before the owner said yes");
                helper.assertTrue(companion.blockPosition().distSqr(start) <= 1, "she set off before asking");
                helper.assertTrue(pending.items().stream().allMatch(i -> i.subject().equals("oak_planks")),
                        "the request does not list the wall: " + pending.items());
                answered[0] = desk(companion).answer(pending.id(),
                        com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
            }
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "goto has not finished");
            helper.assertTrue(record.getResult().success(), "goto failed after the owner allowed it: " + reply);
            helper.assertTrue(plankCount(helper, 7, 7) < planksBefore, "no plank was broken");
            helper.assertTrue(reply.contains("the owner allowed"), "the reply does not say the owner allowed it: " + reply);
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
        });
    }

    /** 同一条路主人说不:goto 以 refused 收场,理由是主人原话;墙一块不少,她没出屋。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void goto_through_owners_wall_denied_quotes_the_owner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ownersRoom(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_squatter", new BlockPos(7, 2, 7), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_strict");
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        TaskRecord record = (TaskRecord) new MovementOps().moveTo(
                (double) target.getX(), (double) target.getY(), (double) target.getZ(), null, anySpec(), null,
                TaskDispatch.ctx("gametest-squatter", companion));
        TaskDispatch.runSync(companion, record, r -> {});
        boolean[] answered = new boolean[1];

        helper.succeedWhen(() -> {
            if (!answered[0]) {
                var pending = desk(companion).pending();
                helper.assertTrue(pending != null, "no consent request before walking through the wall");
                answered[0] = desk(companion).answer(pending.id(),
                        com.dwinovo.numen.permission.ConsentAnswer.Decision.DENY, "别拆我的墙");
            }
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "goto has not finished");
            helper.assertTrue(!record.getResult().success() && reply.contains("refused by the owner")
                    && reply.contains("别拆我的墙"), "the refusal does not quote the owner: " + reply);
            helper.assertTrue(plankCount(helper, 7, 7) == planksBefore, "the wall was damaged after a no");
            helper.assertTrue(companion.blockPosition().distSqr(target) > 3 * 3, "she left anyway");
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
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
        NumenPlayer owner = presentOwner(helper, companion, "gametest_forester");
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        TaskRecord record = new BlockActionOps().autoMine(companion,
                List.of("minecraft:oak_log"), null, 2, null, TaskDispatch.ctx("gametest-lumber", companion));
        TaskDispatch.setTask(companion, record, null, reply -> {});
        java.util.Set<Long> requests = new java.util.HashSet<>();
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            if (pending != null && requests.add(pending.id())) {
                desk(companion).answer(pending.id(), com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
            }
        });

        helper.succeedWhen(() -> {
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "mine has not finished");
            helper.assertTrue(record.getResult().success(), "mine failed after the owner allowed: " + reply);
            helper.assertTrue(companion.getInventory().countItem(Items.OAK_LOG) >= 2,
                    "companion has not gathered 2 logs: " + reply);
            helper.assertTrue(requests.size() == 1, "asked " + requests.size() + " times for the same kind of log");
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
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
        NumenPlayer owner = presentOwner(helper, companion, "gametest_keeper");
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        TaskRecord record = new BlockActionOps().autoMine(companion,
                List.of("minecraft:jungle_log"), null, 2, null, TaskDispatch.ctx("gametest-hewer", companion));
        TaskDispatch.setTask(companion, record, null, reply -> {});
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            if (pending != null) {
                desk(companion).answer(pending.id(), com.dwinovo.numen.permission.ConsentAnswer.Decision.DENY, "留着当柱子");
            }
        });

        helper.succeedWhen(() -> {
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "mine has not finished");
            helper.assertTrue(!record.getResult().success() && reply.contains("留着当柱子"),
                    "the refusal does not quote the owner: " + reply);
            for (BlockPos rel : logs) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.JUNGLE_LOG),
                        "a log was cut after the owner said no at " + rel.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
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
        NumenPlayer owner = presentOwner(helper, companion, "gametest_warden");
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        TaskRecord record = new BlockActionOps().autoMine(companion,
                List.of("minecraft:acacia_log"), null, 2, null, TaskDispatch.ctx("gametest-ranger", companion));
        TaskDispatch.setTask(companion, record, null, reply -> {});
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        helper.succeedWhen(() -> {
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "mine has not finished");
            helper.assertTrue(companion.getInventory().countItem(Items.ACACIA_LOG) >= 2,
                    "companion has not gathered 2 logs: " + reply);
            for (BlockPos rel : placed) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.ACACIA_LOG),
                        "a player-placed log was cut while wild ones stood nearby at " + rel.toShortString());
            }
            helper.assertTrue(!asked[0], "asked the owner although wild logs were there");
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
        });
    }

    /** mine 点名这些团(不给 count,挖完为止),后台派出。 */
    private static TaskRecord mineGroups(NumenPlayer companion, String callId, List<String> groups) {
        TaskRecord record = new BlockActionOps().autoMine(companion, null, groups, null, null,
                TaskDispatch.ctx(callId, companion));
        TaskDispatch.setTask(companion, record, null, reply -> {});
        return record;
    }

    /**
     * 玩家放的原木柱贴着一棵野树:scan_blocks 给出两团。柱子那团要问主人(玩家放的)、野树那团放行,
     * 各自逐格列出,两团不串格。
     */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_permission")
    public static void scan_blocks_splits_a_player_pillar_from_a_wild_tree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> pillar = List.of(new BlockPos(7, 2, 7), new BlockPos(7, 3, 7), new BlockPos(7, 4, 7));
        for (BlockPos rel : pillar) {
            playerPlaces(helper, rel, Items.CHERRY_LOG);
        }
        List<BlockPos> tree = List.of(new BlockPos(8, 2, 7), new BlockPos(8, 3, 7), new BlockPos(8, 4, 7),
                new BlockPos(8, 5, 7));
        for (BlockPos rel : tree) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.CHERRY_LOG.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_surveyor", new BlockPos(4, 2, 7), false);
        String[] reply = scan(companion, 6, "minecraft:cherry_log");

        helper.succeedWhen(() -> {
            helper.assertTrue(reply[0] != null, "scan_blocks has not replied");
            var root = com.google.gson.JsonParser.parseString(reply[0]).getAsJsonObject();
            var groups = root.getAsJsonArray("groups");
            helper.assertTrue(groups.size() == 2 && root.has("groups_total") && root.get("groups_total").getAsInt() == 2,
                    "expected exactly two groups: " + reply[0]);
            var owners = groupHolding(groups, helper.absolutePos(pillar.get(0)));
            var wild = groupHolding(groups, helper.absolutePos(tree.get(0)));
            helper.assertTrue(owners != null && wild != null && owners != wild,
                    "the pillar and the tree are not two groups: " + reply[0]);
            for (BlockPos rel : pillar) {
                helper.assertTrue(groupHolding(groups, helper.absolutePos(rel)) == owners,
                        "a pillar log is not in the pillar's group: " + rel.toShortString());
            }
            for (BlockPos rel : tree) {
                helper.assertTrue(groupHolding(groups, helper.absolutePos(rel)) == wild,
                        "a tree log is not in the tree's group: " + rel.toShortString());
            }
            helper.assertTrue(owners.get("cells").getAsInt() == 3 && "ask".equals(owners.get("permission").getAsString())
                            && owners.get("reason").getAsString().contains("placed by a player"),
                    "the pillar's group does not say breaking it needs the owner: " + owners);
            helper.assertTrue(wild.get("cells").getAsInt() == 4 && "allow".equals(wild.get("permission").getAsString()),
                    "the tree's group is not allowed: " + wild);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * mine groups 只挖点名的团:两棵分开的野树扫成两团,点名近的那团。她挖完那团三格就收场,
     * 另一团一格不少。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void mine_groups_digs_only_the_named_group(GameTestHelper helper) {
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
        String[] reply = scan(companion, 6, "minecraft:dark_oak_log");
        TaskRecord[] mine = new TaskRecord[1];

        helper.succeedWhen(() -> {
            if (mine[0] == null) {
                helper.assertTrue(reply[0] != null, "scan_blocks has not replied");
                var groups = groupsIn(reply[0]);
                var target = groupHolding(groups, helper.absolutePos(named.get(0)));
                var spared = groupHolding(groups, helper.absolutePos(other.get(0)));
                helper.assertTrue(target != null && spared != null && target != spared,
                        "the two trees are not two groups: " + reply[0]);
                mine[0] = mineGroups(companion, "gametest-feller", List.of(target.get("id").getAsString()));
            }
            String result = mine[0].getResult() == null ? null : mine[0].getResult().message();
            helper.assertTrue(result != null, "mine has not finished");
            helper.assertTrue(mine[0].getResult().success() && result.contains("dug 3/3 cells"),
                    "mine did not dig the named group out: " + result);
            for (BlockPos rel : named) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).isAir(),
                        "a log of the named group is still standing at " + rel.toShortString());
            }
            for (BlockPos rel : other) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.DARK_OAK_LOG),
                        "a log outside the named group was cut at " + rel.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 旧编号明确报错:扫两次,第二次的编号接着往上数;拿第一次的编号去 mine,派发当场拒收(不先受理),
     * 拒收的说法点名那个编号、说出最新一次列了哪些、让她重新扫描;原木一根不少。
     */
    @GameTest(template = "floor16", timeoutTicks = 4000, batch = "numen_permission")
    public static void mine_groups_with_an_old_id_is_told_to_scan_again(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos logRel = new BlockPos(6, 2, 6);
        level.setBlockAndUpdate(helper.absolutePos(logRel), Blocks.MANGROVE_LOG.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_archivist", new BlockPos(3, 2, 6), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        String[][] replies = {scan(companion, 5, "minecraft:mangrove_log"), null};
        String[] ids = new String[2];
        String[] refusal = new String[1];

        helper.succeedWhen(() -> {
            for (int i = 0; i < 2; i++) {
                if (ids[i] != null) {
                    continue;
                }
                helper.assertTrue(replies[i] != null && replies[i][0] != null, "scan " + (i + 1) + " has not replied");
                var group = groupHolding(groupsIn(replies[i][0]), helper.absolutePos(logRel));
                helper.assertTrue(group != null, "scan " + (i + 1) + " did not list the log: " + replies[i][0]);
                ids[i] = group.get("id").getAsString();
                if (i == 0) {
                    replies[1] = scan(companion, 5, "minecraft:mangrove_log");
                } else {
                    helper.assertTrue(Integer.parseInt(ids[1].substring(1)) > Integer.parseInt(ids[0].substring(1)),
                            "a new scan reused an old id: " + ids[0] + " then " + ids[1]);
                    try {
                        mineGroups(companion, "gametest-archivist", List.of(ids[0]));
                        refusal[0] = "(accepted)";
                    } catch (IllegalArgumentException stale) {
                        refusal[0] = stale.getMessage();
                    }
                }
            }
            helper.assertTrue(refusal[0] != null && refusal[0].contains(ids[0]) && refusal[0].contains(ids[1])
                    && refusal[0].contains("scan_blocks again"), "the old id was not refused as stale: " + refusal[0]);
            helper.assertTrue(level.getBlockState(helper.absolutePos(logRel)).is(Blocks.MANGROVE_LOG),
                    "the log was cut under a stale id");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * mine 带 spec 的 avoid_break 作用到挑目标上:两根野生诡异菌柄,avoid_break 点名其中一格、要两个。
     * 她挖了另一根就收场,点名的那格原样立着,回执交代那一格挖不成。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void mine_spec_avoid_break_leaves_that_cell_standing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos freeRel = new BlockPos(5, 2, 5);
        BlockPos keptRel = new BlockPos(9, 2, 5);
        level.setBlockAndUpdate(helper.absolutePos(freeRel), Blocks.WARPED_STEM.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(keptRel), Blocks.WARPED_STEM.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_forager", new BlockPos(7, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        BlockPos kept = helper.absolutePos(keptRel);
        com.google.gson.JsonObject spec = new com.google.gson.JsonObject();
        com.google.gson.JsonArray avoid = new com.google.gson.JsonArray();
        avoid.add(kept.getX() + "," + kept.getY() + "," + kept.getZ());
        spec.add("avoid_break", avoid);
        TaskRecord record = new BlockActionOps().autoMine(companion, List.of("minecraft:warped_stem"), null, 2, spec,
                TaskDispatch.ctx("gametest-forager", companion));
        TaskDispatch.setTask(companion, record, null, reply -> {});

        helper.succeedWhen(() -> {
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "mine has not finished");
            helper.assertTrue(level.getBlockState(helper.absolutePos(freeRel)).isAir(), "the free stem was not mined");
            helper.assertTrue(level.getBlockState(kept).is(Blocks.WARPED_STEM), "the avoid_break cell was mined");
            helper.assertTrue(record.getResult().success() && reply.contains("can't be broken here"),
                    "the reply does not account for the cell the spec kept: " + reply);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 点名的团被拒就停:主人写了 deny 行不许挖绯红菌柄,scan_blocks 把那团标成 deny 并给出理由;mine groups
     * 点名它,任务按拒绝收场、理由是那一行规则,菌柄一根不少,也不弹卡。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void mine_groups_refused_stops_with_the_reason(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> stems = List.of(new BlockPos(6, 2, 6), new BlockPos(6, 3, 6));
        for (BlockPos rel : stems) {
            level.setBlockAndUpdate(helper.absolutePos(rel), Blocks.CRIMSON_STEM.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_objector", new BlockPos(3, 2, 6), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        NumenPlayer owner = presentOwner(helper, companion, "gametest_botanist");
        storeOf(owner).add(com.dwinovo.numen.permission.Verdict.Kind.DENY,
                com.dwinovo.numen.permission.Rule.parse("break(minecraft:crimson_stem)"));
        String[] reply = scan(companion, 5, "minecraft:crimson_stem");
        TaskRecord[] mine = new TaskRecord[1];
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        helper.succeedWhen(() -> {
            if (mine[0] == null) {
                helper.assertTrue(reply[0] != null, "scan_blocks has not replied");
                var group = groupHolding(groupsIn(reply[0]), helper.absolutePos(stems.get(0)));
                helper.assertTrue(group != null && "deny".equals(group.get("permission").getAsString())
                                && group.get("reason").getAsString().contains("denied by rule"),
                        "the scan does not mark the denied group: " + reply[0]);
                mine[0] = mineGroups(companion, "gametest-objector", List.of(group.get("id").getAsString()));
            }
            String result = mine[0].getResult() == null ? null : mine[0].getResult().message();
            helper.assertTrue(result != null, "mine has not finished");
            helper.assertTrue(!mine[0].getResult().success() && result.contains("denied by rule"),
                    "the refusal does not carry the rule: " + result);
            for (BlockPos rel : stems) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.CRIMSON_STEM),
                        "a denied stem was cut at " + rel.toShortString());
            }
            helper.assertTrue(!asked[0], "a denied group raised a consent card");
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
        });
    }

    /**
     * 她顺路挖掉的点名格算她挖的:四根原木叠成一柱,四面黑曜石围成竖井,她站在柱顶。点名这一团,她只能一路往下
     * 挖着走——每一根都是导航顺路挖掉的,不是站定了挖的。回执说四格都是她挖的,没有一格记成"别人动过"。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void mine_groups_counts_cells_she_broke_on_the_way(GameTestHelper helper) {
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
        NumenPlayer companion = spawnAt(helper, "gametest_sinker", new BlockPos(7, 6, 7), false);
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        String[] reply = scan(companion, 6, "minecraft:stripped_spruce_log");
        TaskRecord[] mine = new TaskRecord[1];

        helper.succeedWhen(() -> {
            if (mine[0] == null) {
                helper.assertTrue(reply[0] != null, "scan_blocks has not replied");
                var group = groupHolding(groupsIn(reply[0]), helper.absolutePos(column.get(0)));
                helper.assertTrue(group != null && group.get("cells").getAsInt() == 4,
                        "the column is not one group of four: " + reply[0]);
                mine[0] = mineGroups(companion, "gametest-sinker", List.of(group.get("id").getAsString()));
            }
            String result = mine[0].getResult() == null ? null : mine[0].getResult().message();
            helper.assertTrue(result != null, "mine has not finished");
            for (BlockPos rel : column) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).isAir(),
                        "a log of the column is still standing at " + rel.toShortString());
            }
            helper.assertTrue(mine[0].getResult().success() && result.contains("dug 4/4 cells")
                    && !result.contains("gone"), "the cells she broke on the way were not counted as hers: " + result);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * interact_at 左键打主人的箱子:动手之前挂一条征询,这次调用悬着;主人允许后箱子没了。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void interact_left_click_on_owners_chest_asks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chestRel = new BlockPos(6, 2, 5);
        playerPlaces(helper, chestRel, Items.CHEST);
        BlockPos chest = helper.absolutePos(chestRel);
        NumenPlayer companion = spawnAt(helper, "gametest_poker", new BlockPos(4, 2, 5), true);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_hoarder");
        TaskRecord[] dig = new TaskRecord[1];
        boolean[] answered = new boolean[1];
        helper.runAfterDelay(5, () -> {
            dig[0] = new BlockActionOps().interactAt("left", chest.getX(), chest.getY(), chest.getZ(), null, null,
                    TaskDispatch.ctx("gametest-poker", companion));
            TaskDispatch.runSync(companion, dig[0], reply -> {});
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(dig[0] != null, "interact_at not dispatched yet");
            if (!answered[0]) {
                var pending = desk(companion).pending();
                helper.assertTrue(pending != null, "no consent request before hitting the owner's chest");
                helper.assertTrue(dig[0].getResult() == null, "the call did not wait for the owner");
                helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST), "the chest broke before the owner said yes");
                answered[0] = desk(companion).answer(pending.id(),
                        com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
            }
            helper.assertTrue(dig[0].getResult() != null, "interact_at has not finished");
            helper.assertTrue(level.getBlockState(chest).isAir(),
                    "the chest is still there after the owner allowed: " + dig[0].getResult().message());
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
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
        NumenPlayer owner = presentOwner(helper, companion, "gametest_swineherd");
        var pig = EntityType.PIG.create(level);
        helper.assertTrue(pig != null, "pig did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(6, 2, 4));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        pig.setCustomName(net.minecraft.network.chat.Component.literal("Wilbur"));
        level.addFreshEntity(pig);
        TaskRecord record = new com.dwinovo.numen.core.tools.CombatOps().attack(
                List.of(pig.getId()), TaskDispatch.ctx("gametest-swineherd", companion));
        TaskDispatch.setTask(companion, record, null, reply -> {});
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

        helper.succeedWhen(() -> {
            helper.assertTrue(denied[0] && record.getResult() != null, "attack has not finished after the no");
            helper.assertTrue(pig.isAlive(), "the pig was hit after the owner said no");
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
        });
    }

    /** drop_items 每次问:调用悬着等主人;允许后东西丢出来。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void drop_items_waits_for_the_owner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_giver", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_receiver");
        companion.getInventory().add(new ItemStack(Items.DIAMOND, 3));
        TaskRecord record = new com.dwinovo.numen.core.tools.InventoryOps().dropItems(
                "minecraft:diamond", 3, TaskDispatch.ctx("gametest-giver", companion));
        TaskDispatch.runSync(companion, record, reply -> {});
        boolean[] answered = new boolean[1];

        helper.succeedWhen(() -> {
            if (!answered[0]) {
                var pending = desk(companion).pending();
                helper.assertTrue(pending != null, "drop_items did not ask");
                helper.assertTrue(record.getResult() == null, "drop_items finished without an answer");
                helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 3, "dropped before the answer");
                answered[0] = desk(companion).answer(pending.id(),
                        com.dwinovo.numen.permission.ConsentAnswer.Decision.ALLOW_ONCE, "");
            }
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null && record.getResult().success(), "drop_items did not finish: " + reply);
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 0, "nothing was dropped");
            helper.assertTrue(reply.contains("the owner allowed"), "the reply does not say the owner allowed: " + reply);
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
        });
    }

    /**
     * 卡片挂着时主人按停止:drop_items 悬着等答复,主人没点卡片而是按了停止(与 CancelTasksPayload 同一个入口)。
     * 这件活按主人停止收场、消息写明是主人停的,挂着的征询随之撤掉,东西一件没丢。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void an_owner_stop_while_a_card_is_up_withdraws_the_card(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_hesitant", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_changed_mind");
        companion.getInventory().add(new ItemStack(Items.GOLD_INGOT, 4));
        TaskRecord record = new com.dwinovo.numen.core.tools.InventoryOps().dropItems(
                "minecraft:gold_ingot", 4, TaskDispatch.ctx("gametest-hesitant", companion));
        TaskDispatch.runSync(companion, record, reply -> {});
        boolean[] stopped = new boolean[1];

        helper.succeedWhen(() -> {
            if (!stopped[0]) {
                helper.assertTrue(desk(companion).pending() != null, "drop_items did not ask");
                com.dwinovo.numen.task.CompanionTickDispatcher.cancelFor(companion);
                stopped[0] = true;
            }
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "the stopped drop has not settled");
            helper.assertTrue(!record.getResult().success() && reply.startsWith("the owner pressed Stop"),
                    "the result does not say the owner stopped it: " + reply);
            helper.assertTrue(desk(companion).pending() == null, "the card is still up after the stop");
            helper.assertTrue(companion.getInventory().countItem(Items.GOLD_INGOT) == 4, "dropped after the stop");
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
        });
    }

    /** 没人答复:到点按拒绝,理由是"主人不在场,无法征得同意";东西还在身上。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_permission")
    public static void unanswered_consent_times_out_as_denied(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_waiter", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_absent");
        companion.getInventory().add(new ItemStack(Items.EMERALD, 2));
        TaskRecord record = new com.dwinovo.numen.core.tools.InventoryOps().dropItems(
                "minecraft:emerald", 2, TaskDispatch.ctx("gametest-waiter", companion));
        TaskDispatch.runSync(companion, record, reply -> {});

        helper.succeedWhen(() -> {
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "still waiting for the owner");
            helper.assertTrue(!record.getResult().success()
                    && reply.contains(com.dwinovo.numen.permission.ConsentDesk.OWNER_ABSENT),
                    "a timeout must refuse as the owner being absent: " + reply);
            helper.assertTrue(companion.getInventory().countItem(Items.EMERALD) == 2, "dropped without consent");
            helper.assertTrue(desk(companion).pending() == null, "the card is still up");
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
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
        NumenPlayer companion = spawnAt(helper, "gametest_watcher", new BlockPos(3, 2, 7), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_viewer");
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        com.dwinovo.numen.permission.Permission.setMode(companion, com.dwinovo.numen.permission.Mode.OBSERVE);
        TaskRecord record = new BlockActionOps().autoMine(companion,
                List.of("minecraft:birch_log"), null, 2, null, TaskDispatch.ctx("gametest-watch", companion));
        TaskDispatch.setTask(companion, record, null, reply -> {});
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        helper.succeedWhen(() -> {
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "mine has not finished");
            helper.assertTrue(!record.getResult().success() && reply.contains("observe mode"),
                    "observe mode must refuse with its reason: " + reply);
            helper.assertTrue(!asked[0], "observe mode asked the owner");
            for (BlockPos rel : logs) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).is(Blocks.BIRCH_LOG),
                        "observe mode cut a log at " + rel.toShortString());
            }
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
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
        NumenPlayer owner = presentOwner(helper, companion, "gametest_trusting");
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));
        com.dwinovo.numen.permission.Permission.setMode(companion, com.dwinovo.numen.permission.Mode.BYPASS);
        TaskRecord record = new BlockActionOps().autoMine(companion,
                List.of("minecraft:spruce_log"), null, 2, null, TaskDispatch.ctx("gametest-trusted", companion));
        TaskDispatch.setTask(companion, record, null, reply -> {});
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= desk(companion).pending() != null);

        helper.succeedWhen(() -> {
            helper.assertTrue(companion.getInventory().countItem(Items.SPRUCE_LOG) >= 2,
                    "bypass mode did not let her cut the player-placed logs");
            for (BlockPos rel : placed) {
                helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).isAir(),
                        "a log is still standing at " + rel.toShortString());
            }
            helper.assertTrue(!asked[0], "bypass mode asked the owner");
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
        });
    }

    /** 以 {@code who} 的身份跑一条命令,和在聊天栏里敲的一样;回话(成功与失败的)收进返回的列表。 */
    private static List<String> runAs(NumenPlayer who, String command) {
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

    private static com.dwinovo.numen.permission.PermissionStore storeOf(NumenPlayer owner) {
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
        NumenPlayer owner = presentOwner(helper, companion, "gametest_quarry");
        TaskRecord[] calls = new TaskRecord[3];
        int[] step = {0};
        helper.runAfterDelay(5, () -> {
            calls[0] = click(helper, companion, "left", firstRel, "gametest-mason-1");
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
                    if (calls[0].getResult() != null) {
                        helper.assertTrue(calls[0].getResult().success(),
                                "the first dig failed after allow-and-remember: " + calls[0].getResult().message());
                        calls[1] = click(helper, companion, "left", secondRel, "gametest-mason-2");
                        step[0] = 2;
                    }
                }
                case 2 -> {
                    helper.assertTrue(pending == null, "asked again for a remembered kind: " + pending);
                    if (calls[1].getResult() != null) {
                        helper.assertTrue(calls[1].getResult().success(),
                                "the second cobblestone was not dug: " + calls[1].getResult().message());
                        calls[2] = click(helper, companion, "left", plankRel, "gametest-mason-3");
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

        helper.succeedWhen(() -> {
            helper.assertTrue(step[0] == 4 && calls[2].getResult() != null, "the three digs have not finished");
            helper.assertTrue(level.getBlockState(helper.absolutePos(firstRel)).isAir()
                    && level.getBlockState(helper.absolutePos(secondRel)).isAir(), "a cobblestone is still there");
            helper.assertTrue(level.getBlockState(helper.absolutePos(plankRel)).is(Blocks.OAK_PLANKS),
                    "the planks were dug after the owner said no");
            helper.assertTrue(!calls[1].getResult().message().contains("the owner allowed"),
                    "the second dig went through a consent: " + calls[1].getResult().message());
            List<String> allow = storeOf(owner).rules().allow().stream().map(Object::toString).toList();
            helper.assertTrue(allow.equals(List.of("break(placed & minecraft:cobblestone)")),
                    "the owner's allow table is not the remembered row: " + allow);
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
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
        NumenPlayer owner = presentOwner(helper, companion, "gametest_geologist");

        List<String> mistake = runAs(owner, "numen permission rules add ask brek(placed)");
        helper.assertTrue(mistake.stream().anyMatch(m -> m.contains("unknown verb 'brek'") && m.contains("break")),
                "a mistyped rule was not taught: " + mistake);
        List<String> added = runAs(owner, "numen permission rules add ask break(!placed & !block_entity)");
        helper.assertTrue(added.stream().anyMatch(m -> m.contains("Added to ask")), "the row was not added: " + added);
        helper.assertTrue(storeOf(owner).rules().ask().size() == 1, "the owner's ask table: " + storeOf(owner).rules());
        List<String> listed = runAs(owner, "numen permission rules list");
        helper.assertTrue(listed.stream().anyMatch(m -> m.contains("1. break(!placed & !block_entity)")
                && m.contains("Factory rules")), "the list does not show both layers: " + listed);

        TaskRecord[] call = new TaskRecord[1];
        boolean[] answered = new boolean[1];
        helper.runAfterDelay(5, () -> call[0] = click(helper, companion, "left", stoneRel, "gametest-sculptor"));
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            if (pending != null && !answered[0]) {
                helper.assertTrue(pending.items().get(0).rule().equals("break(!placed & !block_entity)"),
                        "asked under another rule: " + pending.items());
                answered[0] = desk(companion).answer(pending.id(),
                        com.dwinovo.numen.permission.ConsentAnswer.Decision.DENY, "石头别动");
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(call[0] != null && call[0].getResult() != null, "interact_at has not finished");
            helper.assertTrue(answered[0], "the owner's ask row did not raise a card");
            helper.assertTrue(!call[0].getResult().success() && call[0].getResult().message().contains("石头别动"),
                    "the refusal does not quote the owner: " + call[0].getResult().message());
            helper.assertTrue(level.getBlockState(helper.absolutePos(stoneRel)).is(Blocks.STONE), "the stone was dug");
            List<String> removed = runAs(owner, "numen permission rules remove ask 1");
            helper.assertTrue(removed.stream().anyMatch(m -> m.contains("Removed from ask"))
                    && storeOf(owner).rules().ask().isEmpty(), "remove did not take the row out: " + removed);
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
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
        NumenPlayer owner = presentOwner(helper, companion, "gametest_beggar");
        NumenPlayer stranger = spawnAt(helper, "gametest_passerby", new BlockPos(12, 2, 12), false);
        companion.getInventory().add(new ItemStack(Items.DIAMOND, 2));
        companion.getInventory().add(new ItemStack(Items.EMERALD, 4));
        var inventory = new com.dwinovo.numen.core.tools.InventoryOps();
        TaskRecord[] calls = new TaskRecord[3];
        int[] step = {0};
        calls[0] = inventory.dropItems("minecraft:diamond", 1, TaskDispatch.ctx("gametest-almoner-1", companion));
        TaskDispatch.runSync(companion, calls[0], reply -> {});
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
                    if (calls[0].getResult() != null) {
                        helper.assertTrue(calls[0].getResult().success()
                                        && calls[0].getResult().message().contains("the owner allowed"),
                                "the card answer did not go through: " + calls[0].getResult().message());
                        calls[1] = inventory.dropItems("minecraft:emerald", 2,
                                TaskDispatch.ctx("gametest-almoner-2", companion));
                        TaskDispatch.runSync(companion, calls[1], reply -> {});
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
                    if (calls[1].getResult() != null) {
                        helper.assertTrue(calls[1].getResult().success()
                                        && calls[1].getResult().message().contains("remembered it"),
                                "the command answer did not go through: " + calls[1].getResult().message());
                        calls[2] = inventory.dropItems("minecraft:emerald", 2,
                                TaskDispatch.ctx("gametest-almoner-3", companion));
                        TaskDispatch.runSync(companion, calls[2], reply -> {});
                        step[0] = 4;
                    }
                }
                case 4 -> {
                    helper.assertTrue(pending == null, "asked again after remembering: " + pending);
                    if (calls[2].getResult() != null) {
                        step[0] = 5;
                    }
                }
                default -> { }
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(step[0] == 5, "the three drops have not finished");
            helper.assertTrue(calls[2].getResult().success(), "the remembered drop failed: " + calls[2].getResult().message());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 1
                    && companion.getInventory().countItem(Items.EMERALD) == 0, "the drops did not all happen");
            helper.assertTrue(storeOf(owner).rules().allow().stream().map(Object::toString).toList()
                    .equals(List.of("drop(minecraft:emerald)")), "the remembered row: " + storeOf(owner).rules().allow());
            List<String> stale = runAs(owner, "numen consent deny 987654321");
            helper.assertTrue(stale.stream().anyMatch(m -> m.contains("No pending consent request")),
                    "answering a missing request did not say so: " + stale);
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
            CompanionFactory.despawn(level.getServer(), stranger);
        });
    }

    /**
     * 放的人照实记,"玩家放的"是"不是她自己放的":同伴 A 放下一块木板,记在 A 名下;A 自己拆是放行(她垫的、
     * 搭的是她的),同伴 B 要拆就得问——别人家同伴搭的东西不是自然方块。
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

    private static TaskRecord takeFirstSlot(NumenPlayer companion, String id) {
        TaskRecord record = new com.dwinovo.numen.core.tools.ContainerOps().transfer(
                List.of(new com.dwinovo.numen.core.tools.ContainerOps.Move(0, null, null)),
                TaskDispatch.ctx(id, companion));
        TaskDispatch.runSync(companion, record, reply -> {});
        return record;
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
        NumenPlayer owner = presentOwner(helper, companion, "gametest_curator");
        TaskRecord[] calls = new TaskRecord[3];
        int[] step = {0};
        boolean[] asked = new boolean[1];
        helper.runAfterDelay(5, () -> {
            List<String> said = runAs(owner, "numen permission mode gametest_peeker observe");
            helper.assertTrue(com.dwinovo.numen.permission.Permission.modeOf(companion)
                    == com.dwinovo.numen.permission.Mode.OBSERVE, "the mode command did not set observe: " + said);
            calls[0] = click(helper, companion, "right", chestRel, "gametest-peeker-1");
            step[0] = 1;
        });
        helper.onEachTick(() -> {
            asked[0] |= desk(companion).pending() != null;
            switch (step[0]) {
                case 1 -> {
                    if (calls[0].getResult() != null) {
                        helper.assertTrue(!calls[0].getResult().success()
                                        && calls[0].getResult().message().contains("observe mode"),
                                "observe mode let her open the chest: " + calls[0].getResult().message());
                        helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "a GUI opened anyway");
                        runAs(owner, "numen permission mode gametest_peeker ask");
                        calls[1] = click(helper, companion, "right", chestRel, "gametest-peeker-2");
                        step[0] = 2;
                    }
                }
                case 2 -> {
                    if (calls[1].getResult() != null) {
                        helper.assertTrue(calls[1].getResult().success()
                                        && companion.containerMenu != companion.inventoryMenu,
                                "the chest did not open in ask mode: " + calls[1].getResult().message());
                        runAs(owner, "numen permission mode gametest_peeker observe");
                        calls[2] = takeFirstSlot(companion, "gametest-peeker-3");
                        step[0] = 3;
                    }
                }
                case 3 -> {
                    if (calls[2].getResult() != null) {
                        step[0] = 4;
                    }
                }
                default -> { }
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(step[0] == 4, "the calls have not finished");
            helper.assertTrue(!calls[2].getResult().success() && calls[2].getResult().message().contains("observe mode"),
                    "observe mode let her take: " + calls[2].getResult().message());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 0, "she took a diamond");
            var box = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chest);
            helper.assertTrue(box.getItem(0).is(Items.DIAMOND) && box.getItem(0).getCount() == 5,
                    "the chest lost its diamonds");
            helper.assertTrue(!asked[0], "observe mode asked the owner");
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
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
        NumenPlayer owner = presentOwner(helper, companion, "gametest_lender");
        runAs(owner, "numen permission rules add ask take(*)");
        TaskRecord[] calls = new TaskRecord[2];
        int[] step = {0};
        helper.runAfterDelay(5, () -> {
            calls[0] = click(helper, companion, "right", chestRel, "gametest-borrower-1");
            step[0] = 1;
        });
        helper.onEachTick(() -> {
            var pending = desk(companion).pending();
            switch (step[0]) {
                case 1 -> {
                    helper.assertTrue(pending == null, "opening the chest asked: " + pending);
                    if (calls[0].getResult() != null) {
                        helper.assertTrue(calls[0].getResult().success()
                                        && companion.containerMenu != companion.inventoryMenu,
                                "the chest did not open: " + calls[0].getResult().message());
                        calls[1] = takeFirstSlot(companion, "gametest-borrower-2");
                        step[0] = 2;
                    }
                }
                case 2 -> {
                    if (pending != null) {
                        helper.assertTrue(pending.items().get(0).rule().equals("take(*)")
                                        && pending.items().get(0).subject().equals("chest"),
                                "asked something else: " + pending.items());
                        helper.assertTrue(calls[1].getResult() == null, "transfer did not wait for the owner");
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

        helper.succeedWhen(() -> {
            helper.assertTrue(step[0] == 3 && calls[1].getResult() != null, "transfer has not finished");
            helper.assertTrue(calls[1].getResult().success(), "transfer failed after allow: "
                    + calls[1].getResult().message());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 3, "the diamonds did not arrive");
            var box = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chest);
            helper.assertTrue(box.getItem(0).isEmpty(), "the chest still holds the diamonds");
            CompanionFactory.despawn(level.getServer(), companion);
            CompanionFactory.despawn(level.getServer(), owner);
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
     * interact_at 以 refused 收场,理由写明服务器没让挖掉,泥土一块不少。生存(STOP 那一下被退)与创造
     * (START 那一下被退)各一具身体。
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
        TaskRecord[] calls = new TaskRecord[2];
        helper.runAfterDelay(5, () -> {
            calls[0] = click(helper, digger, "left", survivalRel, "gametest-trespasser");
            calls[1] = click(helper, builder, "left", creativeRel, "gametest-intruder");
        });

        helper.succeedWhen(() -> {
            for (TaskRecord call : calls) {
                helper.assertTrue(call != null && call.getResult() != null, "a dig has not finished");
                helper.assertTrue(!call.getResult().success()
                                && call.getResult().message().contains(com.dwinovo.numen.core.act.BlockDigger.SERVER_REFUSED),
                        "the bounced break is not reported as refused by the server: " + call.getResult().message());
            }
            helper.assertTrue(level.getBlockState(helper.absolutePos(survivalRel)).is(Blocks.DIRT)
                    && level.getBlockState(helper.absolutePos(creativeRel)).is(Blocks.DIRT), "the protected dirt is gone");
            BreakVeto.LOCKED.remove(digger.getUUID());
            BreakVeto.LOCKED.remove(builder.getUUID());
            CompanionFactory.despawn(level.getServer(), digger);
            CompanionFactory.despawn(level.getServer(), builder);
        });
    }
}
