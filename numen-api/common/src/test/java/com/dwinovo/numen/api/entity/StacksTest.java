package com.dwinovo.numen.api.entity;

import com.mojang.brigadier.exceptions.BuiltInExceptionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 背包里的叠怎么合成"种"、一种怎么描述:物品与组件都相同才算同一种,模型靠描述把同名的几种分开。 */
class StacksTest {

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

    private static ItemStack boxHolding(int diamonds) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, diamonds))));
        return box;
    }

    @Test
    void stacksWithTheSameItemAndComponentsMergeAndTheOthersStayApart() {
        List<Stacks.Kind> kinds = Stacks.kinds(List.of(new ItemStack(Items.SHULKER_BOX), boxHolding(3),
                new ItemStack(Items.DIRT, 5), new ItemStack(Items.SHULKER_BOX), boxHolding(3), boxHolding(4),
                ItemStack.EMPTY, new ItemStack(Items.DIRT, 2)));
        assertEquals(4, kinds.size());
        assertEquals("minecraft:shulker_box", StackLabel.of(kinds.get(0).sample()));
        assertEquals(2, kinds.get(0).count());
        assertEquals("minecraft:shulker_box[holds diamond x3]", StackLabel.of(kinds.get(1).sample()));
        assertEquals(2, kinds.get(1).count());
        assertEquals("minecraft:dirt", StackLabel.of(kinds.get(2).sample()));
        assertEquals(7, kinds.get(2).count());
        assertEquals("minecraft:shulker_box[holds diamond x4]", StackLabel.of(kinds.get(3).sample()));
    }

    @Test
    void whatTellsAStackApartIsSaidAndAPlainItemSaysNothing() {
        assertEquals("", StackLabel.detail(new ItemStack(Items.DIRT)));
        ItemStack named = new ItemStack(Items.DIAMOND_PICKAXE);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Dig"));
        assertEquals("named “Dig”", StackLabel.detail(named));
        ItemStack worn = new ItemStack(Items.DIAMOND_PICKAXE);
        worn.setDamageValue(12);
        assertEquals("damage 12/" + worn.getMaxDamage(), StackLabel.detail(worn));
    }
}
