package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * 做工作台和木镐:空地,她包里 3 块橡木原木——刚好够(12 块木板:工作台 4、木棍 2、木镐 3)。木镐是三乘三的配方,
 * 得先把工作台放下来才做得了,做完再把它挖回包里。成功 = 包里同时有工作台和木镐。
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
                Check.success("包里有工作台和木镐", s -> s.assertTrue(
                        has(s, Items.CRAFTING_TABLE) && has(s, Items.WOODEN_PICKAXE),
                        "工作台 " + s.her().getInventory().countItem(Items.CRAFTING_TABLE) + " 个,木镐 "
                                + s.her().getInventory().countItem(Items.WOODEN_PICKAXE) + " 把")),
                Check.subgoal("做了木板", s -> s.assertTrue(
                        s.her().getInventory().countItem(Items.OAK_LOG) < LOGS, "原木一块没动")),
                Check.subgoal("包里有工作台", s -> s.assertTrue(has(s, Items.CRAFTING_TABLE), "包里没有工作台")),
                Check.subgoal("包里有木镐", s -> s.assertTrue(has(s, Items.WOODEN_PICKAXE), "包里没有木镐")));
    }

    private static boolean has(Scene scene, Item item) {
        return scene.her().getInventory().countItem(item) >= 1;
    }

    @Override
    public List<String> solution(Scene scene) {
        BlockPos table = scene.pos(TABLE);
        String cell = table.getX() + " " + table.getY() + " " + table.getZ();
        return List.of(
                "inv craft oak_planks --count 12",
                "inv craft crafting_table",
                "inv craft stick --count 4",
                "build place " + cell + " --block crafting_table",
                "inv craft wooden_pickaxe",
                "work dig " + cell,
                "work collect");
    }
}
