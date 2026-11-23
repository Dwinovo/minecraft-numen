package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.script.ScriptEngine;
import com.mojang.brigadier.exceptions.BuiltInExceptionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 她带着的一样东西怎么点名:物品 id,或清单里的一项;一项落到背包里是那一格此刻的那一种,对不上就说它过时了。 */
class StackRefTest {

    private static BuiltInExceptionProvider brigadierWords;
    /** 原版的注册表:算一种的记号要把组件照存盘的写法写出来。游戏启动之后才取得到。 */
    private static net.minecraft.core.HolderLookup.Provider REGISTRIES;

    @BeforeAll
    static void boot() {
        brigadierWords = CommandSyntaxException.BUILT_IN_EXCEPTIONS;
        boolean booted;
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            REGISTRIES = net.minecraft.data.registries.VanillaRegistries.createLookup();
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

    @SuppressWarnings("unchecked")
    private static Codec<StackRef> codec() {
        return (Codec<StackRef>) LuaCodecs.of(StackRef.class, SdkFixture.NAMESPACE);
    }

    private static ItemStack boxHolding(int diamonds) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, diamonds))));
        return box;
    }

    @Test
    void anIdNamesTheItemAndIsWrittenBackAsTheId() {
        StackRef ref = codec().decode("shulker_box");
        assertEquals(StackRef.of(Items.SHULKER_BOX), ref);
        assertNull(ref.kind());
        assertEquals("minecraft:shulker_box", codec().encode(ref));
        assertEquals("string|Stack", ScriptEngine.IN_USE.typeText(codec().type()));
    }

    @Test
    void anEntryOfTheListNamesItsKindAndIsWrittenBackAsItemAndKind() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("item", "minecraft:shulker_box");
        entry.put("count", 1L);
        entry.put("details", Map.of("contents", Map.of("minecraft:diamond", 3L)));
        entry.put("kind", "3f9a1c21");
        StackRef ref = codec().decode(entry);
        assertEquals(new StackRef(Items.SHULKER_BOX, "3f9a1c21"), ref);

        Map<String, Object> written = new LinkedHashMap<>();
        written.put("item", "minecraft:shulker_box");
        written.put("kind", "3f9a1c21");
        assertEquals(written, codec().encode(ref));
        assertEquals(ref, codec().decode(codec().encode(ref)));
        assertEquals(StackRef.of(Items.SHULKER_BOX), codec().decode(Map.of("item", "minecraft:shulker_box")),
                "a table without a kind names only the item");
    }

    @Test
    void whatIsNeitherSaysWhatItExpects() {
        BadValue number = assertThrows(BadValue.class, () -> codec().decode(4L));
        assertTrue(number.getMessage().contains("numen.inv.items()"), number.getMessage());
        BadValue other = assertThrows(BadValue.class, () -> codec().decode(Map.of("id", "zombie@3f9a1c")));
        assertTrue(other.getMessage().contains("numen.inv.items()"), other.getMessage());
        BadValue unknown = assertThrows(BadValue.class, () -> codec().decode(Map.of("item", "minecraft:no_such", "kind", "3f9a1c21")));
        assertTrue(unknown.getMessage().contains("field 'item'"), unknown.getMessage());
    }

    @Test
    void anEntryResolvesToItsKindWhereverItLiesAndAStaleOneSaysToReadTheListAgain() {
        Inventory inventory = new Inventory(null);
        inventory.setItem(2, new ItemStack(Items.SHULKER_BOX));
        inventory.setItem(4, boxHolding(3));

        Stacks.Which any = StackRef.of(Items.SHULKER_BOX).in(inventory, REGISTRIES);
        assertNull(any.exact());
        assertTrue(any.matches(inventory.getItem(2)) && any.matches(inventory.getItem(4)));

        StackRef named = new StackRef(Items.SHULKER_BOX, Stacks.code(inventory.getItem(4), REGISTRIES));
        Stacks.Which full = named.in(inventory, REGISTRIES);
        assertTrue(full.matches(inventory.getItem(4)));
        assertFalse(full.matches(inventory.getItem(2)));

        // 两叠换了格,这一项认的还是装着钻石的那一种
        ItemStack empty = inventory.getItem(2);
        inventory.setItem(2, inventory.getItem(4));
        inventory.setItem(4, empty);
        Stacks.Which moved = named.in(inventory, REGISTRIES);
        assertTrue(moved.matches(inventory.getItem(2)));
        assertFalse(moved.matches(inventory.getItem(4)));

        inventory.setItem(2, new ItemStack(Items.DIRT));
        ApiError stale = assertThrows(ApiError.class, () -> named.in(inventory, REGISTRIES));
        assertEquals(ErrorKind.NOT_FOUND, stale.kind());
        assertEquals("numen.inv.items()", stale.hint());
    }
}
