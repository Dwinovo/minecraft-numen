package com.dwinovo.numen.core.pathing.settings;

import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 垫路料清单的解析与归一。需要 MC 注册表。 */
@Tag("mc")
class ScaffoldMaterialsTest {

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

    // ==================== id 解析 ====================

    @Test
    void aBareNameMeansTheVanillaBlock() {
        assumeTrue(booted);
        assertEquals(Items.COBBLESTONE, ScaffoldMaterials.parse("cobblestone"));
        assertEquals(Items.COBBLESTONE, ScaffoldMaterials.parse("minecraft:cobblestone"));
    }

    @Test
    void surroundingSpaceAndCaseAreForgiven() {
        assumeTrue(booted);
        assertEquals(Items.DEEPSLATE, ScaffoldMaterials.parse("  Minecraft:DeepSlate  "));
    }

    @Test
    void somethingThatIsNotABlockIdIsRejectedRatherThanGuessed() {
        assumeTrue(booted);
        assertNull(ScaffoldMaterials.parse("definitely_not_a_block"));
        assertNull(ScaffoldMaterials.parse("not a resource location"));
        assertNull(ScaffoldMaterials.parse(""));
        assertNull(ScaffoldMaterials.parse(null));
    }

    // ==================== 归一 ====================

    /** 顺序就是选料优先级,所以归一不能重排。 */
    @Test
    void theGivenOrderIsKeptBecauseItIsThePickingOrder() {
        assumeTrue(booted);
        assertEquals(List.of("minecraft:stone", "minecraft:dirt", "minecraft:cobblestone"),
                ScaffoldMaterials.normalize(List.of("stone", "dirt", "cobblestone")));
    }

    @Test
    void aRepeatedBlockIsListedOnceAtItsFirstPosition() {
        assumeTrue(booted);
        assertEquals(List.of("minecraft:dirt", "minecraft:stone"),
                ScaffoldMaterials.normalize(
                        List.of("dirt", "minecraft:stone", "dirt", "minecraft:dirt")));
    }

    /** 认不出的悄悄丢掉,调用方回报的是落盘后读回来的那份,模型看得见自己的 id 没生效。 */
    @Test
    void unknownIdsAreDroppedAndTheRestSurvive() {
        assumeTrue(booted);
        assertEquals(List.of("minecraft:dirt"),
                ScaffoldMaterials.normalize(Arrays.asList("nope:whatever", "dirt", null, "")));
    }

    @Test
    void nothingUsableNormalisesToAnEmptyList() {
        assumeTrue(booted);
        assertTrue(ScaffoldMaterials.normalize(List.of("nope:whatever")).isEmpty());
        assertTrue(ScaffoldMaterials.normalize(null).isEmpty());
    }

    // ==================== 出厂默认 ====================

    /** 重力方块垫在半空会直接落下去,出厂默认里一个都不能有。 */
    @Test
    void theFactoryDefaultCarriesNoFallingBlocks() {
        assumeTrue(booted);
        List<String> ids = ScaffoldMaterials.factoryDefaultIds();
        assertFalse(ids.contains("minecraft:sand"), ids.toString());
        assertFalse(ids.contains("minecraft:red_sand"), ids.toString());
        assertFalse(ids.contains("minecraft:gravel"), ids.toString());
    }

    /** 她在深板岩层挖了一小时,背包全是这些——认不出就等于没垫路料。 */
    @Test
    void theFactoryDefaultCoversWhatSheActuallyDigsUp() {
        assumeTrue(booted);
        List<String> ids = ScaffoldMaterials.factoryDefaultIds();
        assertTrue(ids.contains("minecraft:cobbled_deepslate"), ids.toString());
        assertTrue(ids.contains("minecraft:deepslate"), ids.toString());
        assertTrue(ids.contains("minecraft:tuff"), ids.toString());
        assertTrue(ids.contains("minecraft:cobblestone"), ids.toString());
    }

    @Test
    void nothingWithAJobInItIsSpendableByDefault() {
        assumeTrue(booted);
        List<String> ids = ScaffoldMaterials.factoryDefaultIds();
        assertFalse(ids.contains("minecraft:chest"), ids.toString());
        assertFalse(ids.contains("minecraft:crafting_table"), ids.toString());
        assertFalse(ids.contains("minecraft:diamond_block"), ids.toString());
    }
}
