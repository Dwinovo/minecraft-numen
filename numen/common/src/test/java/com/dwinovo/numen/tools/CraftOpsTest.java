package com.dwinovo.numen.tools;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 合成之前看这个格装不装得下这条配方:装不下就说要几乘几的格、去工作台合,不碰背包。 */
@Tag("mc")
class CraftOpsTest {

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** 箱子那样的 3×3 有形配方。 */
    private static ShapedRecipe chest() {
        return new ShapedRecipe("", CraftingBookCategory.MISC,
                ShapedRecipePattern.of(Map.of('#', Ingredient.of(Items.OAK_PLANKS)), "###", "# #", "###"),
                new ItemStack(Items.CHEST));
    }

    /** 不给工作台时用的是她自己的 2×2:箱子要 3×3,说去一张工作台合。 */
    @Test
    void aThreeByThreeRecipeInHerOwnGridSaysToCraftAtATable() {
        assertEquals("chest needs a 3x3 grid; this one is 2x2 — craft it at a crafting table: "
                + "numen.inv.craft(id, {at = cell}) (numen.inv.make finds one)",
                CraftOps.gridTooSmall(chest(), "chest", 2, 2));
    }

    @Test
    void aTableFitsIt() {
        assertNull(CraftOps.gridTooSmall(chest(), "chest", 3, 3));
    }
}
