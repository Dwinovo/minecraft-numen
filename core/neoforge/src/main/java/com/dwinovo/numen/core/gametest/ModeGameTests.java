package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 能力画像:生存与创造各走各的道(移动、挖掘、建造、拿东西)。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class ModeGameTests {

    // ==================== 能力画像用例(生存 / 创造 分道)====================

    /** 画像批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_mode")
    public static void prepareModeBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 创造 goto:无畏/无饥饿画像下移动与疾跑门照常工作。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_mode")
    public static void creative_goto(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_cghost", new BlockPos(2, 2, 2), true);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 13));
        TaskRecord record = call(companion, "goto", args(
                "x", target.getX(),
                "y", target.getY(),
                "z", target.getZ())).task();
        helper.succeedWhen(() -> {
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "creative companion has not reached the goto target");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 创造挖矿:空手(无镐)采金矿——验证三件事:瞬破画像跳过工具门
     * (生存下金矿需铁镐,空手会 WRONG_TOOL 拒工)、无掉落画像按"破坏的
     * 目标方块"计数(背包增量恒零)、以及确实没有掉落物入包。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_mode")
    public static void creative_mine_no_drops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> ores = List.of(
                helper.absolutePos(new BlockPos(8, 2, 8)), helper.absolutePos(new BlockPos(9, 2, 8)),
                helper.absolutePos(new BlockPos(8, 2, 9)), helper.absolutePos(new BlockPos(9, 2, 9)));
        for (BlockPos ore : ores) {
            level.setBlockAndUpdate(ore, Blocks.GOLD_ORE.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_cminer", new BlockPos(2, 2, 2), true);

        TaskRecord record = call(companion, "mine", args(
                "block_ids", List.of("minecraft:gold_ore"),
                "count", 4)).task();

        helper.succeedWhen(() -> {
            for (BlockPos ore : ores) {
                helper.assertTrue(level.getBlockState(ore).isAir(),
                        "gold ore not broken at " + ore.toShortString());
            }
            helper.assertTrue(companion.getInventory().countItem(Items.RAW_GOLD) == 0
                            && companion.getInventory().countItem(Items.GOLD_ORE.asItem()) == 0,
                    "creative mining must not yield drops");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 创造建造:背包全空 + 免耗材记账,想建就建;建完背包依旧全空。 */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_mode")
    public static void creative_build_empty_inventory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_cmason", new BlockPos(2, 2, 2), true);
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (BlockPos rel : boxCells(new BlockPos(8, 2, 8), 3, 1, 3, false)) {
            targets.add(new BuildTaskRecord.Target(Blocks.COBBLESTONE, Items.COBBLESTONE,
                    helper.absolutePos(rel), "cobblestone"));
        }
        var ctx = TaskDispatch.ctx("gametest-cbuild", companion);
        TaskDispatch.setTask(companion, buildJob(ctx.toolCallId(),
                ctx.deadline(3600L), targets, false, false), null, reply -> {});
        helper.succeedWhen(() -> {
            for (BuildTaskRecord.Target t : targets) {
                helper.assertTrue(level.getBlockState(t.pos()).is(Blocks.COBBLESTONE),
                        "structure incomplete at " + t.pos().toShortString());
            }
            helper.assertTrue(companion.getInventory().countItem(Items.COBBLESTONE) == 0,
                    "free-material build must not touch the inventory");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 生存缺料拒工:空背包 + 消耗记账 → 开工前盘料失败,回执逐项报缺。 */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_mode")
    public static void survival_build_missing_materials(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_broke", new BlockPos(2, 2, 2), false);
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (BlockPos rel : boxCells(new BlockPos(8, 2, 8), 3, 1, 3, false)) {
            targets.add(new BuildTaskRecord.Target(Blocks.COBBLESTONE, Items.COBBLESTONE,
                    helper.absolutePos(rel), "cobblestone"));
        }
        var ctx = TaskDispatch.ctx("gametest-sbuild-broke", companion);
        // dispatchAsync 的回调只回"已受理"收条;预检失败落在任务记录的终态上
        BuildTaskRecord record = buildJob(ctx.toolCallId(), ctx.deadline(3600L), targets, true, false);
        TaskDispatch.setTask(companion, record, null, reply -> {});
        helper.succeedWhen(() -> {
            var result = record.getResult();
            helper.assertTrue(result != null && !result.success()
                            && result.message() != null
                            && result.message().contains("not enough materials"),
                    "expected an itemized missing-materials refusal, got: "
                            + (result == null ? "still running" : result.message()));
            for (BuildTaskRecord.Target t : targets) {
                helper.assertTrue(!level.getBlockState(t.pos()).is(Blocks.COBBLESTONE),
                        "must not build anything without materials");
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 空手创造爬井:1×1 黑曜石竖井(徒手黑曜石按不可破计价,四面无路),
     * 背包全空——唯一出路是免耗材画像自动补脚手架泥土后原地垫柱。守两件事:
     * 规划器敢想放置路线(hasThrowaway 画像位)+ 执行层自动补料与垫柱动作。
     * 垫柱是改地形,goto 带 alter=natural 的规格——不带的版本见 goto_refuses_to_tunnel_by_default。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_mode")
    public static void creative_pillar_out_empty_handed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int y = 2; y <= 4; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(3 + dx, y, 3 + dz)),
                            Blocks.OBSIDIAN.defaultBlockState());
                }
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_climber", new BlockPos(3, 2, 3), true);
        BlockPos target = helper.absolutePos(new BlockPos(12, 2, 12));
        TaskRecord record = call(companion, "goto", args(
                "x", target.getX(),
                "y", target.getY(),
                "z", target.getZ(),
                "alter", "natural")).task();
        helper.succeedWhen(() -> {
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "empty-handed creative companion has not pillared out");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 空手创造按她自己的垫路清单变料:清单里只有圆石,困在同一口黑曜石竖井里,照样垫圆石柱爬出来,
     * 背包里也不多出一块清单外的料。
     *
     * <p>钉的是"有料可垫"只有一个判据:规划器认定创造画像有料,执行器取料就得取得出规划器认下的那种。
     * 两边各算一份时,执行器变出一组清单外的泥土、选不出能放的料,垫柱那一步采纳即夭折,重新规划又是
     * 同一条路。用名册里登记过的同伴——清单跟着名册落盘。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_mode")
    public static void creative_pillars_with_her_own_throwaway_list(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(3, 2, 3));
        NumenPlayer companion = com.dwinovo.numen.entity.Companions.summon(level.getServer(),
                java.util.UUID.randomUUID(), "gametest_cobbler", level,
                net.minecraft.world.phys.Vec3.atBottomCenterOf(floor));
        for (int y = 2; y <= 4; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(3 + dx, y, 3 + dz)),
                            Blocks.OBSIDIAN.defaultBlockState());
                }
            }
        }
        companion.teleportTo(floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5);
        companion.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        com.dwinovo.numen.core.pathing.settings.ThrowawayBlocks.store(companion, List.of("minecraft:cobblestone"));
        BlockPos target = helper.absolutePos(new BlockPos(12, 2, 12));
        TaskRecord record = call(companion, "goto", args(
                "x", target.getX(),
                "y", target.getY(),
                "z", target.getZ(),
                "alter", "natural")).task();
        helper.onEachTick(() -> {
            if (record.getResult() != null && !record.getResult().success()) {
                helper.fail("she did not pillar out of the well with her own throwaway blocks: "
                        + record.getResult().message());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "she has not pillared out of the well yet");
            helper.assertTrue(level.getBlockState(floor).is(Blocks.COBBLESTONE),
                    "the pillar is not cobblestone: " + level.getBlockState(floor));
            helper.assertTrue(companion.getInventory().countItem(Items.DIRT) == 0,
                    "she conjured dirt, which is not on her list");
            com.dwinovo.numen.entity.Companions.dismiss(level.getServer(), companion);
        });
    }

    /**
     * 社区图纸格式解码:代码现场构造最小 .litematic(跨 long 位流、YZX 序、
     * 稀疏丢空气)与 .schem v2(varint 数据、带属性的调色板键),写进蓝图目录
     * 经 BlueprintStore 统一管线加载,逐格断言。不提交二进制夹具。
     */
    @GameTest(template = "floor16", timeoutTicks = 6000, batch = "numen_mode")
    public static void blueprint_community_formats(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        java.nio.file.Path dir = com.dwinovo.numen.core.blueprint.BlueprintStore.dir(level.getServer());

        // ---- .litematic:2×1×2,调色板 [air, cobblestone],条目 [1,1,0,1] bits=2 ----
        var region = new net.minecraft.nbt.CompoundTag();
        var pos = new net.minecraft.nbt.CompoundTag();
        pos.putInt("x", 0); pos.putInt("y", 0); pos.putInt("z", 0);
        region.put("Position", pos);
        var size = new net.minecraft.nbt.CompoundTag();
        size.putInt("x", 2); size.putInt("y", 1); size.putInt("z", 2);
        region.put("Size", size);
        var pal = new net.minecraft.nbt.ListTag();
        var air = new net.minecraft.nbt.CompoundTag(); air.putString("Name", "minecraft:air");
        var cob = new net.minecraft.nbt.CompoundTag(); cob.putString("Name", "minecraft:cobblestone");
        pal.add(air); pal.add(cob);
        region.put("BlockStatePalette", pal);
        region.putLongArray("BlockStates", new long[]{0b01000101L});   // [1,1,0,1]
        var regions = new net.minecraft.nbt.CompoundTag();
        regions.put("main", region);
        var liteRoot = new net.minecraft.nbt.CompoundTag();
        liteRoot.put("Regions", regions);
        net.minecraft.nbt.NbtIo.writeCompressed(liteRoot, dir.resolve("fixture_lite.litematic"));

        BlockPos anchor = helper.absolutePos(new BlockPos(4, 4, 4));
        var lite = com.dwinovo.numen.core.blueprint.BlueprintStore.load(level, "fixture_lite", anchor, 0);
        helper.assertTrue(lite.targets().size() == 3, "litematic: expect 3 non-air cells, got "
                + lite.targets().size());
        var litePos = lite.targets().stream().map(BuildTaskRecord.Target::pos).toList();
        helper.assertTrue(litePos.contains(anchor)
                        && litePos.contains(anchor.offset(1, 0, 0))
                        && litePos.contains(anchor.offset(1, 0, 1)),
                "litematic: wrong cell positions " + litePos);
        helper.assertTrue(lite.targets().stream().allMatch(
                        t -> t.desiredState().is(Blocks.COBBLESTONE)),
                "litematic: all cells should be cobblestone");

        // ---- .schem v2:2×1×2,调色板含带属性键,BlockData=[1,1,0,1] ----
        var schemRoot = new net.minecraft.nbt.CompoundTag();
        schemRoot.putInt("Version", 2);
        schemRoot.putShort("Width", (short) 2);
        schemRoot.putShort("Height", (short) 1);
        schemRoot.putShort("Length", (short) 2);
        var spal = new net.minecraft.nbt.CompoundTag();
        spal.putInt("minecraft:air", 0);
        spal.putInt("minecraft:oak_stairs[facing=north]", 1);
        schemRoot.put("Palette", spal);
        schemRoot.putByteArray("BlockData", new byte[]{1, 1, 0, 1});
        net.minecraft.nbt.NbtIo.writeCompressed(schemRoot, dir.resolve("fixture_schem.schem"));

        var schem = com.dwinovo.numen.core.blueprint.BlueprintStore.load(level, "fixture_schem", anchor, 0);
        helper.assertTrue(schem.targets().size() == 3, "schem: expect 3 non-air cells, got "
                + schem.targets().size());
        helper.assertTrue(schem.targets().stream().allMatch(t ->
                        t.desiredState().is(Blocks.OAK_STAIRS)
                                && t.desiredState().getValue(net.minecraft.world.level.block.state
                                        .properties.BlockStateProperties.HORIZONTAL_FACING)
                                == net.minecraft.core.Direction.NORTH),
                "schem: cells should be north-facing oak stairs");
        helper.assertTrue(com.dwinovo.numen.core.blueprint.BlueprintStore.list(level.getServer())
                        .containsAll(List.of("fixture_lite", "fixture_schem")),
                "blueprint list should include community formats");
        helper.succeed();
    }

    /**
     * 真实社区图纸解码:日式小屋(40×23×45,负 z 尺寸区域、379 项调色板
     * 9bit 跨 long 位流)。{@code .litematic} 元数据 TotalBlocks=5859 当金标准。
     *
     * <p>目标格是 5857 而不是 5859:这栋图纸里有<b>两张床</b>,床头不进目标集,
     * 由床脚的落位回调自己造出来。两张床都朝北,而朝北时床头的 z 更小——照原来两半
     * 都排进去的做法,床头会先落位,而床的落位回调会往"朝向再往外一格"再写一块床头,
     * 那一格在目标集之外:一件料换三块床方块,还可能覆写掉已砌好的内墙。所以这个
     * 差额不是解码丢了格,恰恰是它没丢:{@code 5857 + 2 == TotalBlocks}。
     */
    @GameTest(template = "floor16", timeoutTicks = 6000, batch = "numen_mode")
    public static void blueprint_japanese_cottage_decode(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        copyCottageFixture(level);
        var loaded = com.dwinovo.numen.core.blueprint.BlueprintStore.load(
                level, "japanese_cottage", helper.absolutePos(new BlockPos(0, 2, 0)), 0);
        helper.assertTrue(loaded.size().getX() == 40 && loaded.size().getY() == 23
                        && loaded.size().getZ() == 45,
                "cottage size mismatch: " + loaded.size());
        // 5857 个目标格 + 2 个由床脚代建的床头 = TotalBlocks 5859
        helper.assertTrue(loaded.targets().size() == 5857,
                "cottage decode: expect 5857 target cells (TotalBlocks 5859 minus the two bed"
                        + " heads their feet build), got " + loaded.targets().size());
        helper.assertTrue(loaded.dropped() == 0,
                "nothing in this cottage should be dropped outright, got " + loaded.dropped());
        // 床头是被代建的,不是缺了一块设计——目标集里一个都不该有
        helper.assertTrue(loaded.targets().stream().noneMatch(t -> com.dwinovo.numen.core.build
                        .BuildStates.isSecondaryHalf(t.desiredState())),
                "a bed head must never be its own target cell");
        helper.succeed();
    }

    /** 创造取物:inv take 凭空取 100 钻石入背包(创造物品栏 GUI 的假体)。 */
    @GameTest(template = "floor16", timeoutTicks = 6000, batch = "numen_mode")
    public static void creative_take_items(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_conjure", new BlockPos(2, 2, 2), true);
        ToolRun reply = command(companion, "inv take minecraft:diamond 100");
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.reply() != null && reply.reply().contains("\"success\":true"),
                    "inv take should succeed in creative, got: " + reply.reply());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 100,
                    "expected 100 diamonds in inventory");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 生存取物拒绝:inv take 在生存画像下吃诚实拒绝,背包不动。 */
    @GameTest(template = "floor16", timeoutTicks = 6000, batch = "numen_mode")
    public static void survival_take_items_refused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_honest", new BlockPos(2, 2, 2), false);
        ToolRun reply = command(companion, "inv take minecraft:diamond 10");
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.reply() != null && reply.reply().contains("\"success\":false"),
                    "inv take must refuse in survival, got: " + reply.reply());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 0,
                    "survival refusal must not add items");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 生存耗料建造:恰好给足一组圆石,9 格平台建成且背包精确少 9。 */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_mode")
    public static void survival_build_consumes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_frugal", new BlockPos(2, 2, 2), false);
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (BlockPos rel : boxCells(new BlockPos(8, 2, 8), 3, 1, 3, false)) {
            targets.add(new BuildTaskRecord.Target(Blocks.COBBLESTONE, Items.COBBLESTONE,
                    helper.absolutePos(rel), "cobblestone"));
        }
        var ctx = TaskDispatch.ctx("gametest-sbuild", companion);
        TaskDispatch.setTask(companion, buildJob(ctx.toolCallId(),
                ctx.deadline(3600L), targets, true, false), null, reply -> {});
        helper.succeedWhen(() -> {
            for (BuildTaskRecord.Target t : targets) {
                helper.assertTrue(level.getBlockState(t.pos()).is(Blocks.COBBLESTONE),
                        "structure incomplete at " + t.pos().toShortString());
            }
            int left = companion.getInventory().countItem(Items.COBBLESTONE);
            helper.assertTrue(left == 64 - targets.size(),
                    "survival build must consume exactly " + targets.size()
                            + " cobblestone, inventory has " + left);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
