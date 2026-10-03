package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * 做工作台和木镐:空地,她包里 3 块橡木原木——刚好够(12 块木板:工作台 4、木棍 2、木镐 3)。木镐是三乘三的配方,
 * 得先把工作台放下来才做得了。成功 = 包里有木镐,并且有她做的那张工作台:放在场地里或收回包里都算(场地起初一张都没有,
 * 场地里的那张只能是她做的)。
 */
public final class CraftPickaxe implements Scenario {

    private static final int LOGS = 3;
    /** 标准解放工作台的那一格。 */
    private static final BlockPos TABLE = new BlockPos(4, 1, 2);

    @Override
    public String id() {
        return "craft_table_and_pickaxe";
    }

    @Override
    public void setup(Scene scene) {
        scene.give(new ItemStack(Items.OAK_LOG, LOGS));
    }

    @Override
    public String opening() {
        return "用包里的原木做个工作台,再做把木镐。";
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("包里有木镐,工作台在包里或场地里", s -> s.assertTrue(
                        has(s, Items.WOODEN_PICKAXE) && (has(s, Items.CRAFTING_TABLE) || placedTables(s) > 0),
                        "木镐 " + s.her().getInventory().countItem(Items.WOODEN_PICKAXE) + " 把,包里工作台 "
                                + s.her().getInventory().countItem(Items.CRAFTING_TABLE) + " 个,场地里工作台 "
                                + placedTables(s) + " 张")),
                Check.subgoal("做了木板", s -> s.assertTrue(
                        s.her().getInventory().countItem(Items.OAK_LOG) < LOGS, "原木一块没动")),
                Check.subgoal("做了工作台", s -> s.assertTrue(
                        has(s, Items.CRAFTING_TABLE) || placedTables(s) > 0, "包里和场地里都没有工作台")),
                Check.subgoal("包里有木镐", s -> s.assertTrue(has(s, Items.WOODEN_PICKAXE), "包里没有木镐")));
    }

    /** 场地里立着几张工作台。 */
    private int placedTables(Scene scene) {
        int n = 0;
        for (int x = 0; x < arena().size(); x++) {
            for (int z = 0; z < arena().size(); z++) {
                for (int y = 0; y <= arena().height(); y++) {
                    if (scene.level().getBlockState(scene.pos(x, y, z)).is(Blocks.CRAFTING_TABLE)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static boolean has(Scene scene, Item item) {
        return scene.her().getInventory().countItem(item) >= 1;
    }

    @Override
    public String solution(Scene scene) {
        BlockPos table = scene.pos(TABLE);
        String cell = "{x = " + table.getX() + ", y = " + table.getY() + ", z = " + table.getZ() + "}";
        return """
                numen.inv.craft("oak_planks", {count = 12})
                numen.inv.craft("crafting_table")
                numen.inv.craft("stick", {count = 4})
                numen.build.place(%1$s, {block = "crafting_table"})
                numen.inv.craft("wooden_pickaxe")""".formatted(cell);
    }
}
