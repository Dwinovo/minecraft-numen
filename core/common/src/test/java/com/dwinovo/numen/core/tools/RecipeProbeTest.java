package com.dwinovo.numen.core.tools;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 读配方表的安全口钉桩:整合包配方对空输入可以返回 null 而不是抛异常
 * (实测 ATM10 的 gear 配方),枚举层必须把 null、异常、坏输入表都折成
 * "这条配方不可用",绝不外抛。
 */
@Tag("mc")
class RecipeProbeTest {

    private static boolean booted;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    /** 产出为 null、产出直接抛,都折成 EMPTY——调用方只看 isEmpty()。 */
    @Test
    void probeFoldsNullAndThrowIntoEmpty() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过配方探测钉桩");
        assertTrue(RecipeProbe.probe(() -> null).isEmpty());
        assertTrue(RecipeProbe.probe(() -> {
            throw new IllegalStateException("modded recipe exploded");
        }).isEmpty());
        assertTrue(RecipeProbe.resultOf(recipe(null, null), RegistryAccess.EMPTY).isEmpty());
    }

    @Test
    void usableIngredientsRejectsBrokenLists() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过配方探测钉桩");
        assertFalse(RecipeProbe.usableIngredients(recipe(null, null)), "输入表为 null");
        assertTrue(RecipeProbe.usableIngredients(
                recipe(List.of(Ingredient.of(Items.STICK)), new ItemStack(Items.STICK))));
    }

    /** 最小假配方:产出与输入表按参数给,null 就还 null——坏配方就长这样。 */
    private static CraftingRecipe recipe(List<Ingredient> ings, ItemStack result) {
        return new CraftingRecipe() {
            @Override public boolean matches(CraftingInput input, Level level) { return false; }
            @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
                return result;
            }
            @Override public PlacementInfo placementInfo() {
                // 1.21.2+ 输入表经 PlacementInfo 暴露;坏配方的 null 就在这一层还 null
                return ings == null ? null : PlacementInfo.create(ings);
            }
            @Override public RecipeSerializer<? extends CraftingRecipe> getSerializer() { return null; }
            @Override public CraftingBookCategory category() { return CraftingBookCategory.MISC; }
        };
    }
}
