package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * 挖 10 个铁:场地中间一座七乘七、四层高的石堆,里面埋着 12 块铁矿,从外面一块都看不见;她站在石堆边上,包里一把石镐。
 * 主人只说要 10 个铁。要成事得先看见埋着的矿(扫描),走到够得着的地方(开路),再挖(挖掘会自己挖开挡着的石头)、再捡。
 * 成功 = 背包里粗铁不少于 10。
 */
public final class MineIron implements Scenario {

    /** 石堆占的格子(含两端),都在扫描的半径以内。 */
    private static final int LO = 7;
    private static final int HI = 13;
    private static final int TOP = 4;

    @Override
    public String id() {
        return "mine_iron";
    }

    @Override
    public BlockPos start() {
        return new BlockPos(5, 1, 5);
    }

    @Override
    public void setup(Scene scene) {
        for (int x = LO; x <= HI; x++) {
            for (int z = LO; z <= HI; z++) {
                for (int y = 1; y <= TOP; y++) {
                    scene.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        for (BlockPos ore : ores()) {
            scene.set(ore.getX(), ore.getY(), ore.getZ(), Blocks.IRON_ORE.defaultBlockState());
        }
        scene.give(new ItemStack(Items.STONE_PICKAXE));
    }

    /** 12 块铁矿:石堆正中三乘三的芯,底层 9 格加第二层 3 格,四面与上面都隔着至少一格石头。 */
    private static List<BlockPos> ores() {
        List<BlockPos> cells = new java.util.ArrayList<>();
        for (int y = 1; y <= 2; y++) {
            for (int x = 9; x <= 11; x++) {
                for (int z = 9; z <= 11; z++) {
                    if (cells.size() < 12) {
                        cells.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return cells;
    }

    @Override
    public String opening() {
        return "帮我挖 10 个铁回来。";
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("粗铁至少 10 个", s -> s.assertTrue(rawIron(s) >= 10,
                        "背包里只有 " + rawIron(s) + " 个粗铁")),
                Check.subgoal("挖到了铁", s -> s.assertTrue(rawIron(s) >= 1, "一个粗铁都没有")),
                Check.subgoal("粗铁过半", s -> s.assertTrue(rawIron(s) >= 5, "粗铁 " + rawIron(s) + " 个")));
    }

    private static int rawIron(Scene scene) {
        return scene.her().getInventory().countItem(Items.RAW_IRON);
    }

    @Override
    public String solution(Scene scene) {
        // 一轮:先走到一次够得着那一团最多格的地方(挖开石头过去),再挖手够得着的,再扫一次看还剩什么,直到一块不剩
        StringBuilder program = new StringBuilder("local found = numen.scan.blocks(\"iron_ore\", {radius = 12})\n");
        program.append("""
                while #found > 0 do
                  numen.move.to(found[1], {arrive = "dig", costs = {dig = true, place = true, consent = false}})
                  numen.work.dig(found[1])
                  found = numen.scan.blocks("iron_ore", {radius = 12})
                end
                """);
        // 粗铁落在挖空的矿洞里,有的洞只有一格高、不改地形走不进去(numen.work.collect 不挖不放):站进芯的正中(挖开头顶那格)
        // 一圈都捡得到,再把剩下的捡了
        BlockPos core = scene.pos(10, 1, 10);
        program.append("numen.move.to(").append(com.dwinovo.numen.sdk.Positions.literal(core))
                .append(", {costs = {dig = true, place = true, consent = false}})\n");
        program.append("numen.work.collect()\n");
        return program.toString();
    }
}
