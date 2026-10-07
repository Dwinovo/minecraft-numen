package com.dwinovo.numen.entity;

import com.mojang.brigadier.exceptions.BuiltInExceptionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 背包增减的算法:按物品种类合计前后的件数,不看在哪一格、叠成几堆。 */
class BelongingsTest {

    private static BuiltInExceptionProvider brigadierWords;

    @BeforeAll
    static void boot() {
        brigadierWords = CommandSyntaxException.BUILT_IN_EXCEPTIONS;
        boolean booted;
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
        Assumptions.assumeTrue(booted, "Minecraft could not be bootstrapped headless");
    }

    @AfterAll
    static void restore() {
        CommandSyntaxException.BUILT_IN_EXCEPTIONS = brigadierWords;
    }

    private static Belongings snapshot(SimpleContainer inv) {
        Belongings b = new Belongings(inv.getContainerSize());
        b.copyItems(inv);
        return b;
    }

    @Test
    void emptyBucketBecomesMilkBucket() {
        SimpleContainer inv = new SimpleContainer(4);
        inv.setItem(0, new ItemStack(Items.BUCKET));
        Belongings before = snapshot(inv);
        inv.setItem(0, new ItemStack(Items.MILK_BUCKET));
        assertEquals("-1 minecraft:bucket, +1 minecraft:milk_bucket", before.itemChange(inv));
    }

    @Test
    void sameTotalsInOtherSlotsIsNoChange() {
        SimpleContainer inv = new SimpleContainer(4);
        inv.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        inv.setItem(1, new ItemStack(Items.COBBLESTONE, 6));
        Belongings before = snapshot(inv);
        inv.setItem(0, ItemStack.EMPTY);
        inv.setItem(3, new ItemStack(Items.COBBLESTONE, 16));
        inv.setItem(1, ItemStack.EMPTY);
        assertEquals("", before.itemChange(inv));
    }

    @Test
    void countsAddAndRemoveAcrossSlots() {
        SimpleContainer inv = new SimpleContainer(4);
        inv.setItem(0, new ItemStack(Items.BREAD, 2));
        inv.setItem(1, new ItemStack(Items.APPLE, 3));
        Belongings before = snapshot(inv);
        inv.setItem(0, new ItemStack(Items.BREAD, 1));
        inv.setItem(2, new ItemStack(Items.DIAMOND, 2));
        inv.setItem(3, new ItemStack(Items.DIAMOND, 1));
        assertEquals("-1 minecraft:bread, +3 minecraft:diamond", before.itemChange(inv));
    }
}
