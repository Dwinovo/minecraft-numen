package com.dwinovo.numen.api.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.entity.BannerPattern;

/**
 * 一叠东西在模型眼里是什么样:物品 id 一样、数据不一样的几叠(装着钻石的潜影盒与空盒、不同的药水、附魔与没附魔的镐)靠这一句短描述
 * 分开。<b>描述只有这一处</b>:背包清单({@code runtime_state} 与 {@code numen.inv.items})、{@code numen.gui.view} 的槽、
 * 回执里"用了哪一叠"都读它,所以模型在清单里看到的写法与回执里报的是同一个。
 *
 * <p>只说事实,不下结论(该不该喝、值不值钱是她的判断)。已知的数据写成人话(名字、附魔、容器里装着什么、药水效果、旗帜花纹、头颅的
 * 主人、蜂数、耐久损耗);其余的只写它是哪一类(物品数据的名字,模组自己的写成 {@code custom_data}),至少让模型知道这一叠
 * "不是一件白货"。和一件新物品没有任何区别的,描述为空。
 *
 * <p>这一代(1.20.1)的物品数据是一棵 NBT,不是组件:各项按它在 NBT 里的位置读(名字与描述在 {@code display},容器里装的在
 * {@code BlockEntityTag.Items},旗帜花纹在 {@code BlockEntityTag.Patterns}……),写出来的说法和组件那一代一样。
 */
public final class StackLabel {

    /** 容器里最多列几种,其余计数。 */
    private static final int MAX_LISTED = 3;

    /** 下面单独写成人话的那些顶层键;其余的才进"with …"。 */
    private static final Set<String> KNOWN = Set.of("display", "Enchantments", "StoredEnchantments", "BlockEntityTag",
            "Items", "Potion", "CustomPotionEffects", "SkullOwner", "Damage");

    /** 原版其余的顶层键与它们在组件那一代的名字;不在表里的是模组或命令写的,算 {@code custom_data}。 */
    private static final Map<String, String> VANILLA = Map.ofEntries(
            Map.entry("RepairCost", "repair_cost"), Map.entry("Unbreakable", "unbreakable"),
            Map.entry("HideFlags", "hide_additional_tooltip"), Map.entry("CustomModelData", "custom_model_data"),
            Map.entry("Trim", "trim"), Map.entry("Fireworks", "fireworks"), Map.entry("Explosion", "firework_explosion"),
            Map.entry("map", "map_id"), Map.entry("pages", "writable_book_content"), Map.entry("author", "written_book_content"),
            Map.entry("title", "written_book_content"), Map.entry("EntityTag", "entity_data"), Map.entry("Recipes", "recipes"),
            Map.entry("LodestonePos", "lodestone_tracker"), Map.entry("Charged", "charged_projectiles"),
            Map.entry("ChargedProjectiles", "charged_projectiles"), Map.entry("BucketVariantTag", "bucket_entity_data"),
            Map.entry("CanDestroy", "can_break"), Map.entry("CanPlaceOn", "can_place_on"),
            Map.entry("AttributeModifiers", "attribute_modifiers"), Map.entry("Effects", "suspicious_stew_effects"),
            Map.entry("instrument", "instrument"), Map.entry("DebugProperty", "debug_stick_state"));

    private StackLabel() {}

    /** 物品 id,带描述时后接 {@code [描述]}:{@code minecraft:shulker_box[holds diamond x3]}。 */
    public static String of(ItemStack stack) {
        return of(stack, detail(stack));
    }

    /** 同 {@link #of(ItemStack)},描述由调用方给({@link #details} 排过重的)。 */
    public static String of(ItemStack stack, String detail) {
        return wrap(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), detail);
    }

    /** 不带命名空间的 {@link #of}:回执正文里用,{@code shulker_box[holds diamond x3]}。 */
    public static String brief(ItemStack stack) {
        return wrap(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), detail(stack));
    }

    private static String wrap(String id, String detail) {
        return detail.isEmpty() ? id : id + "[" + detail + "]";
    }

    /**
     * 一批互不相同的种({@link Stacks#kinds})各自的描述。区别落在描述没写出来的数据值上(两叠模组数据不同)时,
     * 同物品同描述的几种会写得一样,模型分不出;这里给它们依次加上 {@code variant N},清单与回执里每一种都有自己的写法。
     */
    public static List<String> details(List<ItemStack> kinds) {
        List<String> out = new ArrayList<>();
        for (ItemStack kind : kinds) {
            out.add(detail(kind));
        }
        for (int i = 0; i < kinds.size(); i++) {
            int n = 0;
            for (int j = 0; j < kinds.size(); j++) {
                if (kinds.get(j).is(kinds.get(i).getItem()) && detail(kinds.get(j)).equals(detail(kinds.get(i)))) {
                    n++;
                }
            }
            if (n > 1) {
                int nth = 1;
                for (int j = 0; j < i; j++) {
                    if (kinds.get(j).is(kinds.get(i).getItem()) && detail(kinds.get(j)).equals(detail(kinds.get(i)))) {
                        nth++;
                    }
                }
                String base = detail(kinds.get(i));
                out.set(i, (base.isEmpty() ? "" : base + "; ") + "variant " + nth);
            }
        }
        return out;
    }

    /** 区分这一叠的描述,几条用分号连;和新物品没有区别为空。 */
    public static String detail(ItemStack stack) {
        List<String> parts = new ArrayList<>();
        if (stack.hasCustomHoverName()) {
            parts.add("named “" + stack.getHoverName().getString() + "”");
        }
        Map<Enchantment, Integer> enchants = EnchantmentHelper.deserializeEnchantments(stack.getEnchantmentTags());
        if (!enchants.isEmpty()) {
            parts.add("enchanted " + enchantments(enchants));
        }
        Map<Enchantment, Integer> stored = EnchantmentHelper.deserializeEnchantments(EnchantedBookItem.getEnchantments(stack));
        if (!stored.isEmpty()) {
            parts.add("stores " + enchantments(stored));
        }
        CompoundTag tag = stack.getTag();
        CompoundTag blockEntity = stack.getTagElement("BlockEntityTag");
        if (blockEntity != null) {
            List<ItemStack> held = items(blockEntity.getList("Items", Tag.TAG_COMPOUND));
            if (!held.isEmpty()) {
                parts.add("holds " + contents(held));
            }
        }
        if (tag != null) {
            List<ItemStack> bundled = items(tag.getList("Items", Tag.TAG_COMPOUND));
            if (!bundled.isEmpty()) {
                parts.add("holds " + contents(bundled));
            }
        }
        String brew = brew(stack);
        if (!brew.isEmpty()) {
            parts.add(brew);
        }
        if (blockEntity != null) {
            ListTag patterns = blockEntity.getList("Patterns", Tag.TAG_COMPOUND);
            if (!patterns.isEmpty()) {
                List<String> layers = new ArrayList<>();
                for (int i = 0; i < patterns.size(); i++) {
                    CompoundTag layer = patterns.getCompound(i);
                    String pattern = BannerPattern.byHash(layer.getString("Pattern")) == null ? "unknown"
                            : BannerPattern.byHash(layer.getString("Pattern")).unwrapKey()
                                    .map(key -> key.location().getPath()).orElse("unknown");
                    layers.add(pattern + " " + DyeColor.byId(layer.getInt("Color")).getName());
                }
                parts.add("patterns " + String.join(", ", layers));
            }
            ListTag bees = blockEntity.getList("Bees", Tag.TAG_COMPOUND);
            if (!bees.isEmpty()) {
                parts.add(bees.size() + " bee" + (bees.size() == 1 ? "" : "s"));
            }
        }
        if (tag != null && tag.contains("SkullOwner")) {
            String owner = tag.contains("SkullOwner", Tag.TAG_STRING) ? tag.getString("SkullOwner")
                    : tag.getCompound("SkullOwner").getString("Name");
            parts.add("head of " + (owner.isEmpty() ? "unknown" : owner));
        }
        if (stack.getDamageValue() > 0) {
            parts.add("damage " + stack.getDamageValue() + "/" + stack.getMaxDamage());
        }
        CompoundTag display = stack.getTagElement("display");
        if (display != null && !display.getList("Lore", Tag.TAG_STRING).isEmpty()) {
            parts.add("lore");
        }
        Set<String> others = new LinkedHashSet<>();
        if (tag != null) {
            for (String key : tag.getAllKeys()) {
                if (!KNOWN.contains(key)) {
                    others.add(VANILLA.getOrDefault(key, "custom_data"));
                }
            }
        }
        if (display != null && display.contains("color")) {
            others.add("dyed_color");
        }
        if (!others.isEmpty()) {
            parts.add("with " + String.join(", ", others));
        }
        return String.join("; ", parts);
    }

    private static List<ItemStack> items(ListTag list) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ItemStack s = ItemStack.of(list.getCompound(i));
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String enchantments(Map<Enchantment, Integer> entries) {
        List<String> out = new ArrayList<>();
        entries.forEach((enchantment, level) -> {
            var key = BuiltInRegistries.ENCHANTMENT.getKey(enchantment);
            out.add((key == null ? "unknown" : key.getPath()) + " " + level);
        });
        return String.join(", ", out);
    }

    /** 装着什么:按物品种类合计,先见先列,最多 {@link #MAX_LISTED} 种,其余计数。 */
    private static String contents(Iterable<ItemStack> stacks) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (ItemStack s : stacks) {
            totals.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), Integer::sum);
        }
        List<String> listed = new ArrayList<>();
        totals.forEach((id, n) -> {
            if (listed.size() < MAX_LISTED) {
                listed.add(id + " x" + n);
            }
        });
        String more = totals.size() > MAX_LISTED ? ", +" + (totals.size() - MAX_LISTED) + " more kinds" : "";
        return String.join(", ", listed) + more;
    }

    /**
     * 瓶子里装的是什么。<b>治疗、剧毒、夜视的 item id 全都是 {@code minecraft:potion}</b>,内容在 {@code Potion} 与
     * {@code CustomPotionEffects} 里。印的是原版药水的<b>注册名</b>({@code strong_healing}、{@code long_poison}),不是"安全/危险"那种结论;
     * 酿造出来的、模组的药水没有预设名,效果只在自定义列表里,两处都读,不用维护白名单。
     */
    private static String brew(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || (!tag.contains("Potion") && !tag.contains("CustomPotionEffects"))) {
            return "";
        }
        StringBuilder label = new StringBuilder();
        var potion = PotionUtils.getPotion(stack);
        if (potion != Potions.EMPTY) {
            label.append(BuiltInRegistries.POTION.getKey(potion).getPath());
        }
        for (MobEffectInstance effect : PotionUtils.getCustomEffects(stack)) {
            if (label.length() > 0) {
                label.append('+');
            }
            var key = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect());
            label.append(key == null ? "unknown" : key.getPath());
        }
        return label.toString();
    }
}
