package com.dwinovo.numen.pathing.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 挖一格要什么工具、哪几件算适格、候选的先后。 */
class ToolsTest {

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    @Test
    void onlyBlocksThatNeedOneNeedATool() {
        assertTrue(Tools.needsTool(Blocks.STONE.defaultBlockState()));
        assertTrue(Tools.needsTool(Blocks.IRON_ORE.defaultBlockState()));
        assertFalse(Tools.needsTool(Blocks.DIRT.defaultBlockState()));
        assertFalse(Tools.needsTool(Blocks.OAK_LOG.defaultBlockState()));
    }

    @Test
    void theCandidateListIsTheToolsThatHarvestItInCheapMaterialOrder() {
        List<Item> stone = Tools.candidates(Blocks.STONE.defaultBlockState());
        assertEquals(Items.WOODEN_PICKAXE, stone.get(0), "石头木镐就够,先做最便宜的");
        assertTrue(stone.contains(Items.STONE_PICKAXE) && stone.contains(Items.IRON_PICKAXE)
                && stone.contains(Items.DIAMOND_PICKAXE));
        assertFalse(stone.contains(Items.WOODEN_AXE), "斧子挖不动石头");
        assertFalse(stone.contains(Items.WOODEN_SHOVEL));

        List<Item> iron = Tools.candidates(Blocks.IRON_ORE.defaultBlockState());
        assertEquals(Items.STONE_PICKAXE, iron.get(0), "铁矿要石镐以上,木镐不在候选里");
        assertFalse(iron.contains(Items.WOODEN_PICKAXE));
        assertFalse(iron.contains(Items.GOLDEN_PICKAXE), "金镐也是木档,挖不了铁矿");

        List<Item> log = Tools.candidates(Blocks.OAK_LOG.defaultBlockState());
        assertEquals(Items.WOODEN_AXE, log.get(0), "原木要斧");
    }

    @Test
    void aSwordIsNotAToolThatHarvests() {
        assertFalse(Tools.harvests(new ItemStack(Items.IRON_SWORD), Blocks.STONE.defaultBlockState()));
        assertFalse(Tools.harvests(ItemStack.EMPTY, Blocks.STONE.defaultBlockState()));
        assertFalse(Tools.harvests(new ItemStack(Items.WOODEN_PICKAXE), Blocks.IRON_ORE.defaultBlockState()),
                "木镐等级不够");
        assertTrue(Tools.harvests(new ItemStack(Items.WOODEN_PICKAXE), Blocks.STONE.defaultBlockState()));
        assertTrue(Tools.harvests(new ItemStack(Items.STONE_PICKAXE), Blocks.IRON_ORE.defaultBlockState()));
        assertTrue(Tools.harvests(Items.IRON_PICKAXE, Blocks.DIAMOND_ORE.defaultBlockState()));
    }
}
