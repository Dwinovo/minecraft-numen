package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import com.dwinovo.numen.sdk.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * 从原木做到石镐:她空手,包里 3 块橡木原木,旁边一小堆露天的石头。要成事得先做工作台和木镐(石头没有木镐挖不下来),
 * 挖三块圆石,再回工作台做石镐。成功 = 包里有石镐。
 *
 * <p>3 块原木 = 12 块木板:工作台 4、木棍 4(两把镐各要 2 根,合成一次出 4 根,用 2 块板)、木镐 3,共 9 块,余 3 块;
 * 石镐用的是挖来的圆石,不再吃木板。工作台放在地上就行,不用挖回。
 */
public final class CraftStonePickaxe implements Scenario {

    private static final int LOGS = 3;
    /** 标准解放工作台的那一格。 */
    private static final BlockPos TABLE = new BlockPos(4, 1, 2);
    /** 石堆:三乘三、两层,四面露天。 */
    private static final BlockPos PILE_LO = new BlockPos(8, 1, 8);
    private static final BlockPos PILE_HI = new BlockPos(10, 2, 10);

    @Override
    public String id() {
        return "craft_stone_pickaxe";
    }

    @Override
    public void setup(Scene scene) {
        for (int x = PILE_LO.getX(); x <= PILE_HI.getX(); x++) {
            for (int y = PILE_LO.getY(); y <= PILE_HI.getY(); y++) {
                for (int z = PILE_LO.getZ(); z <= PILE_HI.getZ(); z++) {
                    scene.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        scene.give(new ItemStack(Items.OAK_LOG, LOGS));
    }

    @Override
    public String opening() {
        return "给我做把石镐。";
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("包里有石镐", s -> s.assertTrue(has(s, Items.STONE_PICKAXE),
                        "石镐 " + s.her().getInventory().countItem(Items.STONE_PICKAXE) + " 把")),
                Check.subgoal("做了工作台", s -> s.assertTrue(
                        has(s, Items.CRAFTING_TABLE) || placedTables(s) > 0, "包里和场地里都没有工作台")),
                Check.subgoal("做了木镐", s -> s.assertTrue(has(s, Items.WOODEN_PICKAXE) || has(s, Items.STONE_PICKAXE),
                        "包里没有木镐")),
                Check.subgoal("挖到了圆石", s -> s.assertTrue(
                        has(s, Items.COBBLESTONE) || has(s, Items.STONE_PICKAXE) || pileStone(s) < pileSize(),
                        "石堆一块没动")));
    }

    private static int pileSize() {
        return (PILE_HI.getX() - PILE_LO.getX() + 1) * (PILE_HI.getY() - PILE_LO.getY() + 1)
                * (PILE_HI.getZ() - PILE_LO.getZ() + 1);
    }

    /** 石堆里还剩几块石头。 */
    private static int pileStone(Scene scene) {
        int n = 0;
        for (int x = PILE_LO.getX(); x <= PILE_HI.getX(); x++) {
            for (int y = PILE_LO.getY(); y <= PILE_HI.getY(); y++) {
                for (int z = PILE_LO.getZ(); z <= PILE_HI.getZ(); z++) {
                    n += scene.level().getBlockState(scene.pos(x, y, z)).is(Blocks.STONE) ? 1 : 0;
                }
            }
        }
        return n;
    }

    private int placedTables(Scene scene) {
        return Tally.blocks(scene, arena(), Blocks.CRAFTING_TABLE);
    }

    private static boolean has(Scene scene, Item item) {
        return scene.her().getInventory().countItem(item) >= 1;
    }

    @Override
    public String solution(Scene scene) {
        BlockPos table = scene.pos(TABLE);
        // 站在石堆南面,石堆上层北排三块都在手边
        BlockPos stand = scene.pos(9, 1, 7);
        BlockPos top = scene.pos(PILE_LO.getX(), PILE_HI.getY(), PILE_LO.getZ());
        return """
                numen.inv.make("oak_planks", 12)
                numen.inv.make("crafting_table")
                numen.inv.make("stick", 4)
                numen.build.place({{name = "crafting_table", pos = %1$s}})
                numen.inv.make("wooden_pickaxe")
                numen.move.to(%2$s)
                numen.work.dig({%3$s, %4$s, %5$s})
                numen.work.collect()
                numen.inv.make("stone_pickaxe")""".formatted(Positions.literal(table), Positions.literal(stand),
                Positions.literal(top), Positions.literal(top.east()), Positions.literal(top.east(2)));
    }
}
