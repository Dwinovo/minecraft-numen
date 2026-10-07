package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Budget;
import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import com.dwinovo.numen.sdk.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * 从零做到铁镐:她空手,场地里有三棵树(各四格原木)、一堆露天的石头、四块露天的铁矿和两块露天的煤矿。要成事得砍树做木板、
 * 工作台、木棍、木镐,挖圆石做石镐,再用八块圆石做熔炉,挖铁矿、挖煤,把粗铁烧成铁锭,最后回工作台做铁镐——一条十几步的
 * 长链条,每一步的产物是下一步的原料。成功 = 包里有铁镐;子目标按里程碑:工作台、木镐、石镐、熔炉、铁锭、铁镐(高一级的东西做出来
 * 就说明低一级的已经做过)。
 */
public final class IronPickaxeChain implements Scenario {

    private static final Budget BUDGET = new Budget(60, 20);
    private static final List<BlockPos> TREES = List.of(new BlockPos(5, 1, 4), new BlockPos(8, 1, 3),
            new BlockPos(4, 1, 8));
    private static final int TRUNK = 4;
    /** 石堆:四乘三、两层。 */
    private static final BlockPos STONE_LO = new BlockPos(10, 1, 9);
    private static final BlockPos STONE_HI = new BlockPos(13, 2, 11);
    /** 铁矿:地上两乘二;煤矿:地上一排两块。 */
    private static final BlockPos IRON_LO = new BlockPos(13, 1, 4);
    private static final BlockPos IRON_HI = new BlockPos(14, 1, 5);
    private static final BlockPos COAL_LO = new BlockPos(7, 1, 13);
    private static final BlockPos COAL_HI = new BlockPos(8, 1, 13);
    /** 标准解放熔炉的那一格,和它站的那一格。 */
    private static final BlockPos FURNACE = new BlockPos(7, 1, 7);
    private static final BlockPos FURNACE_STAND = new BlockPos(7, 1, 6);

    @Override
    public String id() {
        return "iron_pickaxe_chain";
    }

    @Override
    public Budget budget() {
        return BUDGET;
    }

    @Override
    public void setup(Scene scene) {
        for (BlockPos base : TREES) {
            for (int y = 0; y < TRUNK; y++) {
                scene.set(base.getX(), base.getY() + y, base.getZ(), Blocks.OAK_LOG.defaultBlockState());
            }
        }
        fill(scene, STONE_LO, STONE_HI, Blocks.STONE);
        fill(scene, IRON_LO, IRON_HI, Blocks.IRON_ORE);
        fill(scene, COAL_LO, COAL_HI, Blocks.COAL_ORE);
    }

    private static void fill(Scene scene, BlockPos lo, BlockPos hi, Block block) {
        for (int x = lo.getX(); x <= hi.getX(); x++) {
            for (int y = lo.getY(); y <= hi.getY(); y++) {
                for (int z = lo.getZ(); z <= hi.getZ(); z++) {
                    scene.set(x, y, z, block.defaultBlockState());
                }
            }
        }
    }

    @Override
    public String opening() {
        return "给我做一把铁镐。";
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("包里有铁镐", s -> s.assertTrue(has(s, Items.IRON_PICKAXE),
                        "铁镐 " + s.her().getInventory().countItem(Items.IRON_PICKAXE) + " 把")),
                Check.subgoal("工作台", s -> s.assertTrue(has(s, Items.CRAFTING_TABLE)
                        || Tally.blocks(s, arena(), Blocks.CRAFTING_TABLE) > 0 || has(s, Items.WOODEN_PICKAXE),
                        "包里和场地里都没有工作台")),
                Check.subgoal("木镐", s -> s.assertTrue(has(s, Items.WOODEN_PICKAXE) || has(s, Items.STONE_PICKAXE)
                        || has(s, Items.IRON_PICKAXE), "没有木镐")),
                Check.subgoal("石镐", s -> s.assertTrue(has(s, Items.STONE_PICKAXE) || has(s, Items.IRON_PICKAXE),
                        "没有石镐")),
                Check.subgoal("熔炉", s -> s.assertTrue(has(s, Items.FURNACE)
                        || Tally.blocks(s, arena(), Blocks.FURNACE) > 0 || has(s, Items.IRON_INGOT)
                        || has(s, Items.IRON_PICKAXE), "没有熔炉")),
                Check.subgoal("铁锭", s -> s.assertTrue(has(s, Items.IRON_INGOT) || has(s, Items.IRON_PICKAXE),
                        "没有铁锭")),
                Check.subgoal("铁镐", s -> s.assertTrue(has(s, Items.IRON_PICKAXE), "没有铁镐")));
    }

    private static boolean has(Scene scene, Item item) {
        return scene.her().getInventory().countItem(item) >= 1;
    }

    @Override
    public String solution(Scene scene) {
        BlockPos stoneLo = scene.pos(STONE_LO);
        BlockPos stoneHi = scene.pos(STONE_HI);
        return """
                local S = numen.shape
                numen.work.mine(numen.scan.blocks("oak_log", {radius = 16})[1])
                numen.inv.make("oak_planks", 12)
                numen.inv.make("crafting_table")
                numen.inv.make("stick", 6)
                numen.inv.make("wooden_pickaxe")
                numen.work.mine(S.box(%1$s, %2$s))
                numen.inv.make("stone_pickaxe")
                numen.work.mine(numen.scan.blocks("coal_ore", {radius = 16})[1])
                numen.work.mine(numen.scan.blocks("iron_ore", {radius = 16})[1])
                numen.inv.make("furnace")
                numen.move.to(%3$s)
                numen.build.place({{name = "furnace", pos = %4$s}})
                numen.inv.smelt(%4$s, "raw_iron", 3)
                numen.inv.make("iron_pickaxe")""".formatted(Positions.literal(stoneLo), Positions.literal(stoneHi),
                Positions.literal(scene.pos(FURNACE_STAND)), Positions.literal(scene.pos(FURNACE)));
    }
}
