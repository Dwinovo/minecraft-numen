package com.dwinovo.numen.tools.inventory;

import com.dwinovo.numen.api.knowledge.ItemFacts;

import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 物品的事实读自默认组件:能耗损的有耐久、能吃的有饱食值、方块物品说出放下的方块。 */
@Tag("mc")
class VanillaKnowledgeTest {

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

    @Test
    void aToolHasDurabilityAndNoFood() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过物品事实钉桩");
        ItemFacts facts = VanillaKnowledge.facts(Items.IRON_PICKAXE);
        assertEquals("minecraft:iron_pickaxe", facts.item());
        assertEquals(1, facts.maxStack());
        assertEquals(Optional.of(250), facts.durability());
        assertEquals(Optional.empty(), facts.food());
        assertEquals(Optional.empty(), facts.places());
    }

    @Test
    void foodSaysWhatItGivesAndABlockItemWhichBlockItPlaces() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过物品事实钉桩");
        ItemFacts apple = VanillaKnowledge.facts(Items.GOLDEN_APPLE);
        assertEquals(4, apple.food().orElseThrow().nutrition());
        assertEquals(Optional.empty(), apple.durability());
        ItemFacts log = VanillaKnowledge.facts(Items.OAK_LOG);
        assertEquals(Optional.of("minecraft:oak_log"), log.places());
        assertEquals(64, log.maxStack());
    }
}
