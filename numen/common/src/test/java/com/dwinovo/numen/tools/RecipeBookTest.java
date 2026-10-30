package com.dwinovo.numen.tools;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 配方书对原版以外的配方类型的读法:只靠 {@link Recipe} 接口。模组的机器配方是一个原版不认识的类,这里用同样的东西顶它——不属于
 * 合成、烧炼、切石、锻造任何一族的 {@code Recipe} 实现(类型 id 借原版注册表里已有的一个,单测里注册表已冻结,没法再登记新的)。
 */
@Tag("mc")
class RecipeBookTest {

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

    /** 没人为合成格造过的配方:一个类别、几样料、一件产物。 */
    private record Machine(NonNullList<Ingredient> ings, ItemStack result) implements Recipe<SingleRecipeInput> {
        @Override public boolean matches(SingleRecipeInput input, Level level) { return false; }
        @Override public ItemStack assemble(SingleRecipeInput input, HolderLookup.Provider registries) { return result; }
        @Override public boolean canCraftInDimensions(int width, int height) { return true; }
        @Override public ItemStack getResultItem(HolderLookup.Provider registries) { return result; }
        @Override public NonNullList<Ingredient> getIngredients() { return ings; }
        @Override public RecipeSerializer<?> getSerializer() { return null; }
        @Override public RecipeType<?> getType() { return RecipeType.STONECUTTING; }
    }

    private static RecipeHolder<?> holder(String id, Recipe<?> recipe) {
        return new RecipeHolder<>(ResourceLocation.parse("gametest:" + id), recipe);
    }

    private static Machine machine(ItemStack result, Ingredient... in) {
        return new Machine(NonNullList.of(Ingredient.EMPTY, in), result);
    }

    private static RecipeBook.Found collect(List<RecipeHolder<?>> holders, java.util.function.BiPredicate<Recipe<?>, ItemStack> wanted) {
        return RecipeBook.collect(holders, RegistryAccess.EMPTY, wanted, (h, r, res) -> {
            throw new AssertionError("no crafting recipe in this list");
        });
    }

    /** 不认识的类别也读得出:配方 id、类别、产物与件数、每一样输入。 */
    @Test
    void anUnknownKindOfRecipeGivesTypeInputsAndProduct() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过配方书钉桩");
        List<RecipeHolder<?>> all = List.of(
                holder("press", machine(new ItemStack(Items.IRON_NUGGET, 9), Ingredient.of(Items.IRON_INGOT))),
                holder("other", machine(new ItemStack(Items.GOLD_NUGGET), Ingredient.of(Items.GOLD_INGOT))));

        RecipeBook.Found found = collect(all, (r, result) -> result.getItem() == Items.IRON_NUGGET);

        assertEquals(1, found.recipes().size());
        com.dwinovo.numen.tools.Recipe row = found.recipes().get(0);
        assertEquals("gametest:press", row.id());
        assertEquals("minecraft:stonecutting", row.category());
        assertEquals("minecraft:iron_nugget", row.item());
        assertEquals(9, row.makes());
        assertEquals(List.of("iron_ingot"), row.ingredients());
        assertEquals(Optional.empty(), found.omitted());
    }

    /** 按输入找:有这样东西当料的才列出,产物不论。 */
    @Test
    void usesFindsTheRecipesThatTakeTheItem() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过配方书钉桩");
        List<RecipeHolder<?>> all = List.of(
                holder("a", machine(new ItemStack(Items.STICK), Ingredient.of(Items.COAL), Ingredient.of(Items.IRON_INGOT))),
                holder("b", machine(new ItemStack(Items.STICK), Ingredient.of(Items.GOLD_INGOT))));
        ItemStack iron = new ItemStack(Items.IRON_INGOT);

        RecipeBook.Found found = collect(all, (r, result) -> RecipeBook.takes(r, iron));

        assertEquals(List.of("gametest:a"), found.recipes().stream().map(com.dwinovo.numen.tools.Recipe::id).toList());
        assertEquals(List.of("coal", "iron_ingot"), found.recipes().get(0).ingredients());
    }

    /** 一条坏配方(输入表为 null、产出为 null)只丢它自己,别的照常列出。 */
    @Test
    void aBrokenRecipeOnlyDropsItself() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过配方书钉桩");
        List<RecipeHolder<?>> all = List.of(
                holder("no_inputs", new Machine(null, new ItemStack(Items.STICK))),
                holder("no_result", new Machine(NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.COAL)), null)),
                holder("fine", machine(new ItemStack(Items.STICK), Ingredient.of(Items.COAL))));

        RecipeBook.Found found = collect(all, (r, result) -> true);

        assertEquals(List.of("gametest:fine"), found.recipes().stream().map(com.dwinovo.numen.tools.Recipe::id).toList());
    }

    /** 条数封顶,超出的数如实说。 */
    @Test
    void theListIsCappedAndSaysHowManyMore() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过配方书钉桩");
        List<RecipeHolder<?>> all = new ArrayList<>();
        for (int i = 0; i < RecipeBook.MAX_RECIPES + 5; i++) {
            all.add(holder("r" + i, machine(new ItemStack(Items.STICK), Ingredient.of(Items.COAL))));
        }

        RecipeBook.Found found = collect(all, (r, result) -> true);

        assertEquals(RecipeBook.MAX_RECIPES, found.recipes().size());
        assertEquals(Optional.of(5), found.omitted());
    }

    /** 一类料很大时只列前几种,说还有几种;小的原样列全。 */
    @Test
    void aBigIngredientIsListedShortAndSaysHowManyMore() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过配方书钉桩");
        assertEquals("any[stone/dirt/coal/iron_ingot]",
                RecipeBook.describeIngredient(Ingredient.of(Items.STONE, Items.DIRT, Items.COAL, Items.IRON_INGOT)));
        String big = RecipeBook.describeIngredient(Ingredient.of(Items.STONE, Items.DIRT, Items.COAL, Items.IRON_INGOT,
                Items.GOLD_INGOT, Items.STICK, Items.BOWL, Items.APPLE, Items.BREAD, Items.CLAY_BALL));
        assertTrue(big.endsWith("/+2 more]"), big);
        assertFalse(big.contains("bread"), big);
    }

    /** 锻造配方不列输入,但按输入找时看它的三个槽认不认这样东西;产物是配方展示的产出。 */
    @Test
    void aSmithingRecipeIsFoundByItsInputSlotsAndByItsProduct() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过配方书钉桩");
        List<RecipeHolder<?>> all = List.of(holder("upgrade", new SmithingTransformRecipe(
                Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), Ingredient.of(Items.DIAMOND_PICKAXE),
                Ingredient.of(Items.NETHERITE_INGOT), new ItemStack(Items.NETHERITE_PICKAXE))));
        ItemStack ingot = new ItemStack(Items.NETHERITE_INGOT);

        RecipeBook.Found byInput = collect(all, (r, result) -> RecipeBook.takes(r, ingot));
        RecipeBook.Found byProduct = collect(all, (r, result) -> result.getItem() == Items.NETHERITE_PICKAXE);

        assertEquals(1, byInput.recipes().size());
        assertEquals("minecraft:smithing", byInput.recipes().get(0).category());
        assertEquals("minecraft:netherite_pickaxe", byInput.recipes().get(0).item());
        assertEquals(1, byProduct.recipes().size());
    }
}
