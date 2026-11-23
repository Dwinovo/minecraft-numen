package com.dwinovo.numen.pathing.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 从目标往下递归的"可做"判定:原木→木板→木棍→工作台→工具,材料够不够、产出件数、坏配方。 */
class CraftPlanTest {

    private static Map<Item, List<CraftPlan.Shape>> recipes() {
        // 静态字段会在 @BeforeAll 之前初始化,那时原版注册表还没起来:料在方法里现取
        Ingredient planksItem = Ingredient.of(Items.OAK_PLANKS);
        Ingredient log = Ingredient.of(Items.OAK_LOG);
        CraftPlan.Shape planks = new CraftPlan.Shape(id("oak_planks"), Items.OAK_PLANKS, 4, 0, 0,
                List.of(log), 2);
        CraftPlan.Shape sticks = new CraftPlan.Shape(id("stick"), Items.STICK, 4, 1, 2,
                List.of(planksItem, planksItem), 2);
        CraftPlan.Shape table = new CraftPlan.Shape(id("crafting_table"), Items.CRAFTING_TABLE, 1, 2, 2,
                List.of(planksItem, planksItem, planksItem, planksItem), 2);
        CraftPlan.Shape pickaxe = new CraftPlan.Shape(id("wooden_pickaxe"), Items.WOODEN_PICKAXE, 1, 3, 3,
                List.of(planksItem, planksItem, planksItem, Ingredient.EMPTY, Ingredient.of(Items.STICK), Ingredient.EMPTY,
                        Ingredient.EMPTY, Ingredient.of(Items.STICK), Ingredient.EMPTY), 3);
        return Map.of(
                Items.OAK_PLANKS, List.of(planks),
                Items.STICK, List.of(sticks),
                Items.CRAFTING_TABLE, List.of(table),
                Items.WOODEN_PICKAXE, List.of(pickaxe));
    }

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("numen_test", path);
    }

    @Test
    void plansTheWholeChainFromLogs() {
        Optional<CraftPlan.Plan> plan = CraftPlan.plan(Items.WOODEN_PICKAXE, recipes(), Map.of(Items.OAK_LOG, 3));
        assertTrue(plan.isPresent());
        List<CraftPlan.Step> steps = plan.get().steps();
        assertEquals(4, steps.size(), "两块木板、一次木棍、一次镐:" + steps);
        assertEquals(Items.OAK_PLANKS, steps.get(0).shape().result());
        assertEquals(Items.OAK_PLANKS, steps.get(1).shape().result());
        assertEquals(Items.STICK, steps.get(2).shape().result());
        assertEquals(Items.WOODEN_PICKAXE, steps.get(3).shape().result());
        assertEquals(1, plan.get().left().getOrDefault(Items.OAK_LOG, 0), "三根原木用两根");
        assertEquals(3, plan.get().left().getOrDefault(Items.OAK_PLANKS, 0), "十二块木板用九块");
        assertEquals(2, plan.get().left().getOrDefault(Items.STICK, 0));
    }

    @Test
    void withoutTheMaterialsThereIsNoPlan() {
        assertFalse(CraftPlan.plan(Items.WOODEN_PICKAXE, recipes(), Map.of()).isPresent());
        assertFalse(CraftPlan.plan(Items.WOODEN_PICKAXE, recipes(), Map.of(Items.OAK_LOG, 1)).isPresent(),
                "一根原木只够四块木板,木板加木棍要五块");
    }

    @Test
    void oneCraftCoversSeveralOfTheSameIngredient() {
        Optional<CraftPlan.Plan> plan = CraftPlan.plan(Items.STICK, recipes(), Map.of(Items.OAK_PLANKS, 2));
        assertTrue(plan.isPresent());
        assertEquals(1, plan.get().steps().size(), "一次木棍出一出四根");
        assertEquals(0, plan.get().left().getOrDefault(Items.OAK_PLANKS, 0));
        assertEquals(3, plan.get().left().getOrDefault(Items.STICK, 0), "要一根,做出四根,还剩三根");
    }

    @Test
    void aCraftingTableIsOnePlanksCraftPlusTheTable() {
        Optional<CraftPlan.Plan> plan = CraftPlan.plan(Items.CRAFTING_TABLE, recipes(), Map.of(Items.OAK_LOG, 1));
        assertTrue(plan.isPresent());
        assertEquals(2, plan.get().steps().size());
        assertEquals(Items.OAK_PLANKS, plan.get().steps().get(0).shape().result());
        assertEquals(Items.CRAFTING_TABLE, plan.get().steps().get(1).shape().result());
        assertEquals(0, plan.get().left().getOrDefault(Items.OAK_LOG, 0));
    }

    @Test
    void theTablePlanLeavesEnoughForTheToolPlan() {
        Map<Item, Integer> pool = Map.of(Items.OAK_LOG, 3);
        CraftPlan.Plan table = CraftPlan.plan(Items.CRAFTING_TABLE, recipes(), pool).orElseThrow();
        CraftPlan.Plan tool = CraftPlan.plan(Items.WOODEN_PICKAXE, recipes(), table.left()).orElseThrow();
        assertEquals(2, table.steps().size(), "一块木板 + 工作台");
        assertEquals(Items.WOODEN_PICKAXE, tool.steps().get(tool.steps().size() - 1).shape().result());
        assertEquals(0, tool.left().getOrDefault(Items.OAK_LOG, 0), "三根原木刚好:一根做台,两根做镐");
    }
}
