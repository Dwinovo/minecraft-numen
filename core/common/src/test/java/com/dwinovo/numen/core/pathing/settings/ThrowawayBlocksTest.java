package com.dwinovo.numen.core.pathing.settings;

import com.dwinovo.numen.core.init.InitTag;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** throwaway 清单的解析与归一。需要 MC 注册表。 */
@Tag("mc")
class ThrowawayBlocksTest {

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
        assertEquals(Items.COBBLESTONE, ThrowawayBlocks.parse("cobblestone"));
        assertEquals(Items.COBBLESTONE, ThrowawayBlocks.parse("minecraft:cobblestone"));
    }

    @Test
    void surroundingSpaceAndCaseAreForgiven() {
        assumeTrue(booted);
        assertEquals(Items.DEEPSLATE, ThrowawayBlocks.parse("  Minecraft:DeepSlate  "));
    }

    @Test
    void somethingThatIsNotABlockIdIsRejectedRatherThanGuessed() {
        assumeTrue(booted);
        assertNull(ThrowawayBlocks.parse("definitely_not_a_block"));
        assertNull(ThrowawayBlocks.parse("not a resource location"));
        assertNull(ThrowawayBlocks.parse(""));
        assertNull(ThrowawayBlocks.parse(null));
    }

    // ==================== 归一 ====================

    /** 顺序就是选料优先级,所以归一不能重排。 */
    @Test
    void theGivenOrderIsKeptBecauseItIsThePickingOrder() {
        assumeTrue(booted);
        assertEquals(List.of("minecraft:stone", "minecraft:dirt", "minecraft:cobblestone"),
                ThrowawayBlocks.normalize(List.of("stone", "dirt", "cobblestone")));
    }

    @Test
    void aRepeatedBlockIsListedOnceAtItsFirstPosition() {
        assumeTrue(booted);
        assertEquals(List.of("minecraft:dirt", "minecraft:stone"),
                ThrowawayBlocks.normalize(
                        List.of("dirt", "minecraft:stone", "dirt", "minecraft:dirt")));
    }

    /** 认不出的悄悄丢掉,调用方回报的是落盘后读回来的那份,模型看得见自己的 id 没生效。 */
    @Test
    void unknownIdsAreDroppedAndTheRestSurvive() {
        assumeTrue(booted);
        assertEquals(List.of("minecraft:dirt"),
                ThrowawayBlocks.normalize(Arrays.asList("nope:whatever", "dirt", null, "")));
    }

    @Test
    void nothingUsableNormalisesToAnEmptyList() {
        assumeTrue(booted);
        assertTrue(ThrowawayBlocks.normalize(List.of("nope:whatever")).isEmpty());
        assertTrue(ThrowawayBlocks.normalize(null).isEmpty());
    }

    // ==================== 出厂默认 ====================

    /**
     * 出厂默认是一条<b>标签引用</b>,不是展开后的清单。这样整合包改
     * {@code numen:throwaway} 就能改掉所有新同伴的起点,而静态常量读不到数据包——
     * 存引用、用时再解析,才躲得开那个时序。清单内容本身由 datagen 那份定义,
     * 它的判据(不含重力方块、不含有功能的方块)在 {@code ModItemTagData} 那边钉。
     */
    @Test
    void theFactoryDefaultIsATagReferenceSoPacksCanChangeIt() {
        assumeTrue(booted);
        assertEquals(List.of("#numen:throwaway"), ThrowawayBlocks.factoryDefaultIds());
    }

    /** 标签引用必须真的解析得开——认不出就等于所有新同伴一件垫路料都没有。 */
    @Test
    void thatReferenceResolvesToRealItems() {
        assumeTrue(booted);
        String ref = ThrowawayBlocks.factoryDefaultIds().get(0);
        assertNotNull(InitTag.parseRef(net.minecraft.core.registries.Registries.ITEM, ref), ref);
    }
}
