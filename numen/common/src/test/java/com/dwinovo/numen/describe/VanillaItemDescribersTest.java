package com.dwinovo.numen.describe;

import com.dwinovo.numen.api.describe.AnswerText;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.brigadier.exceptions.BuiltInExceptionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原版的物品回答者说的字段,和它们照 {@link AnswerText} 写在 id 后面的样子。每个回答者只说这一叠和一件新物品不一样的地方:附魔、存着的
 * 附魔、里面装着什么、药水、起的名字与说明、旗帜图案、头颅的主人、蜂数、用掉的耐久;一件白货(空着的潜影盒、没用过的镐、没装东西的
 * 药水瓶)谁也不说话。需要 MC 注册表。
 */
class VanillaItemDescribersTest {

    private static BuiltInExceptionProvider brigadierWords;
    /** 附魔与旗帜图案是数据包里的注册表:从原版的那一份里取。 */
    private static HolderLookup.Provider vanilla;

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
        vanilla = VanillaRegistries.createLookup();
    }

    @AfterAll
    static void restore() {
        CommandSyntaxException.BUILT_IN_EXCEPTIONS = brigadierWords;
    }

    /** 一个回答者说的,照她读到的样子写在这一叠的 id 后面。 */
    private static String label(ItemStack stack, String name, Map<String, Object> said) {
        return AnswerText.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), Map.of(name, said));
    }

    private static Holder<Enchantment> enchantment(net.minecraft.resources.ResourceKey<Enchantment> key) {
        return vanilla.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    /** 没人说话:null 或空表。 */
    private static boolean silent(Map<String, Object> said) {
        return said == null || said.isEmpty();
    }

    @Test
    void anEnchantedSwordSaysItsEnchantmentsByIdAndLevel() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(enchantment(Enchantments.SHARPNESS), 5);
        sword.enchant(enchantment(Enchantments.UNBREAKING), 3);
        Map<String, Object> said = VanillaDescribers.enchantments(null, sword);
        assertEquals(Map.of("minecraft:sharpness", 5, "minecraft:unbreaking", 3), said);
        assertEquals("minecraft:diamond_sword[enchantments={minecraft:sharpness=5, minecraft:unbreaking=3}]",
                label(sword, "enchantments", said));
        assertTrue(silent(VanillaDescribers.storedEnchantments(null, sword)), "附在剑上的不是存着的");
    }

    @Test
    void anEnchantedBookSaysWhatItStores() {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        stored.set(enchantment(Enchantments.MENDING), 1);
        book.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
        Map<String, Object> said = VanillaDescribers.storedEnchantments(null, book);
        assertEquals(Map.of("minecraft:mending", 1), said);
        assertEquals("minecraft:enchanted_book[stored_enchantments={minecraft:mending=1}]",
                label(book, "stored_enchantments", said));
        assertTrue(silent(VanillaDescribers.enchantments(null, book)));
    }

    @Test
    void aShulkerBoxSaysWhatItHoldsTotalledByItem() {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 3),
                new ItemStack(Items.DIRT), new ItemStack(Items.DIAMOND, 2))));
        Map<String, Object> said = VanillaDescribers.contents(null, box);
        assertEquals(Map.of("minecraft:diamond", 5, "minecraft:dirt", 1), said);
        assertEquals(List.of("minecraft:diamond", "minecraft:dirt"), List.copyOf(said.keySet()), "先见先列");
        assertEquals("minecraft:shulker_box[contents={minecraft:diamond=5, minecraft:dirt=1}]",
                label(box, "contents", said));
    }

    @Test
    void aBundleSaysWhatItHoldsTheSameWay() {
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStack(Items.STICK, 4))));
        assertEquals(Map.of("minecraft:stick", 4), VanillaDescribers.contents(null, bundle));
    }

    @Test
    void aPotionSaysWhichPotionItIsAndVariantsKeepTheirOwnIds() {
        ItemStack strong = new ItemStack(Items.POTION);
        strong.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRONG_HEALING));
        Map<String, Object> said = VanillaDescribers.potion(null, strong);
        assertEquals(Map.of("id", "minecraft:strong_healing"), said);
        assertEquals("minecraft:potion[potion={id=\"minecraft:strong_healing\"}]", label(strong, "potion", said));

        ItemStack poison = new ItemStack(Items.POTION);
        poison.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.POISON));
        assertEquals(Map.of("id", "minecraft:poison"), VanillaDescribers.potion(null, poison),
                "物品 id 都是 minecraft:potion,靠这一项分开");
    }

    @Test
    void aPotionWithOnlyAddedEffectsListsThem() {
        ItemStack brewed = new ItemStack(Items.POTION);
        brewed.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.empty(),
                List.of(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200))));
        assertEquals(Map.of("effects", List.of("minecraft:speed")), VanillaDescribers.potion(null, brewed));
    }

    @Test
    void aUsedToolSaysHowMuchDurabilityIsGone() {
        ItemStack worn = new ItemStack(Items.DIAMOND_PICKAXE);
        worn.setDamageValue(12);
        Map<String, Object> said = VanillaDescribers.durability(null, worn);
        assertEquals(Map.of("used", 12, "max", worn.getMaxDamage()), said);
        assertEquals("minecraft:diamond_pickaxe[durability={used=12, max=" + worn.getMaxDamage() + "}]",
                label(worn, "durability", said));
    }

    @Test
    void aNameAndLoreAreSaidAsTheyRead() {
        ItemStack named = new ItemStack(Items.DIAMOND_PICKAXE);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Dig"));
        assertEquals(Map.of("name", "Dig"), VanillaDescribers.display(null, named));
        named.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("old"), Component.literal("sharp"))));
        Map<String, Object> said = VanillaDescribers.display(null, named);
        assertEquals(Map.of("name", "Dig", "lore", List.of("old", "sharp")), said);
        assertEquals("minecraft:diamond_pickaxe[display={name=\"Dig\", lore={\"old\", \"sharp\"}}]",
                label(named, "display", said));
    }

    @Test
    void aBannerListsItsLayersFromTheBottom() {
        ItemStack banner = new ItemStack(Items.WHITE_BANNER);
        banner.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers(List.of(new BannerPatternLayers.Layer(
                vanilla.lookupOrThrow(Registries.BANNER_PATTERN).getOrThrow(BannerPatterns.STRIPE_BOTTOM), DyeColor.RED))));
        Map<String, Object> said = VanillaDescribers.banner(null, banner);
        assertEquals(Map.of("layers", List.of(Map.of("pattern", "minecraft:stripe_bottom", "color", "red"))), said);
        assertEquals("minecraft:white_banner[banner={layers={{pattern=\"minecraft:stripe_bottom\", color=\"red\"}}}]",
                label(banner, "banner", said));
    }

    @Test
    void aHeadSaysWhoseItIsAndAHiveHowManyBees() {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, new ResolvableProfile(Optional.of("Notch"), Optional.empty(), new PropertyMap()));
        Map<String, Object> whose = VanillaDescribers.head(null, head);
        assertEquals(Map.of("owner", "Notch"), whose);
        assertEquals("minecraft:player_head[head={owner=\"Notch\"}]", label(head, "head", whose));

        ItemStack nest = new ItemStack(Items.BEE_NEST);
        nest.set(DataComponents.BEES, List.of(new BeehiveBlockEntity.Occupant(CustomData.EMPTY, 0, 0),
                new BeehiveBlockEntity.Occupant(CustomData.EMPTY, 0, 0)));
        Map<String, Object> bees = VanillaDescribers.bees(null, nest);
        assertEquals(Map.of("count", 2), bees);
        assertEquals("minecraft:bee_nest[bees={count=2}]", label(nest, "bees", bees));
    }

    /** 和一件新物品没有区别的一叠,九个回答者都不说话:物品自带的默认组件(潜影盒的空容器、药水瓶的空内容)不算区别。 */
    @Test
    void aPlainItemGetsNoAnswerFromAnyOfThem() {
        for (ItemStack plain : List.of(new ItemStack(Items.DIRT, 5), new ItemStack(Items.SHULKER_BOX),
                new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.POTION), new ItemStack(Items.ENCHANTED_BOOK),
                new ItemStack(Items.BUNDLE), new ItemStack(Items.WHITE_BANNER), new ItemStack(Items.PLAYER_HEAD),
                new ItemStack(Items.BEE_NEST))) {
            String which = plain.toString();
            assertTrue(silent(VanillaDescribers.display(null, plain)), which);
            assertTrue(silent(VanillaDescribers.enchantments(null, plain)), which);
            assertTrue(silent(VanillaDescribers.storedEnchantments(null, plain)), which);
            assertTrue(silent(VanillaDescribers.contents(null, plain)), which);
            assertTrue(silent(VanillaDescribers.potion(null, plain)), which);
            assertTrue(silent(VanillaDescribers.banner(null, plain)), which);
            assertTrue(silent(VanillaDescribers.head(null, plain)), which);
            assertTrue(silent(VanillaDescribers.bees(null, plain)), which);
            assertTrue(silent(VanillaDescribers.durability(null, plain)), which);
        }
    }
}
