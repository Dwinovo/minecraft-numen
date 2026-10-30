package com.dwinovo.numen.api.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * 一叠东西在模型眼里是什么样:物品 id 一样、组件不一样的几叠(装着钻石的潜影盒与空盒、不同的药水、附魔与没附魔的镐)靠这一句短描述
 * 分开。<b>描述只有这一处</b>:背包清单({@code runtime_state} 与 {@code numen.inv.items})、{@code numen.gui.view} 的槽、
 * 回执里"用了哪一叠"都读它,所以模型在清单里看到的写法与回执里报的是同一个。
 *
 * <p>只说事实,不下结论(该不该喝、值不值钱是她的判断)。已知的组件写成人话(名字、附魔、容器里装着什么、药水效果、旗帜花纹、头颅的
 * 主人、蜂数、耐久损耗);其余组件只写组件名,至少让模型知道这一叠"不是一件白货"。和一件新物品没有任何区别的,描述为空
 * (空着的潜影盒、熔炉都是白货:原版里容器类物品默认带一个空的容器组件,空的不算区别)。
 */
public final class StackLabel {

    /** 容器里最多列几种,其余计数。 */
    private static final int MAX_LISTED = 3;

    private StackLabel() {}

    /** 物品 id,带描述时后接 {@code [描述]}:{@code minecraft:shulker_box[holds diamond x3]}。 */
    public static String of(ItemStack stack) {
        return wrap(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), detail(stack));
    }

    /** 不带命名空间的 {@link #of}:回执正文里用,{@code shulker_box[holds diamond x3]}。 */
    public static String brief(ItemStack stack) {
        return wrap(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), detail(stack));
    }

    private static String wrap(String id, String detail) {
        return detail.isEmpty() ? id : id + "[" + detail + "]";
    }

    /** 区分这一叠的描述,几条用分号连;和新物品没有区别为空。 */
    public static String detail(ItemStack stack) {
        List<String> parts = new ArrayList<>();
        var name = stack.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            parts.add("named “" + name.getString() + "”");
        }
        var enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants != null && !enchants.isEmpty()) {
            parts.add("enchanted " + enchantments(enchants.entrySet()));
        }
        var stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored != null && !stored.isEmpty()) {
            parts.add("stores " + enchantments(stored.entrySet()));
        }
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null && container.nonEmptyStream().findAny().isPresent()) {
            parts.add("holds " + contents(container.nonEmptyItems()));
        }
        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) {
            parts.add("holds " + contents(bundle.items()));
        }
        String brew = brew(stack);
        if (!brew.isEmpty()) {
            parts.add(brew);
        }
        var banner = stack.get(DataComponents.BANNER_PATTERNS);
        if (banner != null && !banner.layers().isEmpty()) {
            List<String> layers = new ArrayList<>();
            banner.layers().forEach(l -> layers.add(path(l.pattern()) + " " + l.color().getName()));
            parts.add("patterns " + String.join(", ", layers));
        }
        var profile = stack.get(DataComponents.PROFILE);
        if (profile != null) {
            parts.add("head of " + profile.name().orElse("unknown"));
        }
        var bees = stack.get(DataComponents.BEES);
        if (bees != null && !bees.isEmpty()) {
            parts.add(bees.size() + " bee" + (bees.size() == 1 ? "" : "s"));
        }
        if (stack.getDamageValue() > 0) {
            parts.add("damage " + stack.getDamageValue() + "/" + stack.getMaxDamage());
        }
        var lore = stack.get(DataComponents.LORE);
        if (lore != null && !lore.lines().isEmpty()) {
            parts.add("lore");
        }
        List<String> others = new ArrayList<>();
        stack.getComponentsPatch().entrySet().forEach(e -> {
            if (e.getValue().isPresent() && !known(e.getKey())) {
                others.add(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(e.getKey()).getPath());
            }
        });
        if (!others.isEmpty()) {
            parts.add("with " + String.join(", ", others));
        }
        return String.join("; ", parts);
    }

    /** 上面单独写成人话的那些组件;其余的才进"with …"。 */
    private static boolean known(DataComponentType<?> type) {
        return type == DataComponents.CUSTOM_NAME || type == DataComponents.ENCHANTMENTS
                || type == DataComponents.STORED_ENCHANTMENTS || type == DataComponents.CONTAINER
                || type == DataComponents.BUNDLE_CONTENTS || type == DataComponents.POTION_CONTENTS
                || type == DataComponents.BANNER_PATTERNS || type == DataComponents.PROFILE
                || type == DataComponents.BEES || type == DataComponents.DAMAGE || type == DataComponents.LORE;
    }

    private static String enchantments(Iterable<? extends Map.Entry<? extends Holder<?>, Integer>> entries) {
        List<String> out = new ArrayList<>();
        entries.forEach(e -> out.add(path(e.getKey()) + " " + e.getValue()));
        return String.join(", ", out);
    }

    private static String path(Holder<?> holder) {
        return holder.unwrapKey().map(key -> key.location().getPath()).orElse("unknown");
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
     * 瓶子里装的是什么。<b>治疗、剧毒、夜视的 item id 全都是 {@code minecraft:potion}</b>,内容在 {@code POTION_CONTENTS}
     * 组件里。印的是原版药水的<b>注册名</b>({@code strong_healing}、{@code long_poison}),不是"安全/危险"那种结论;
     * 酿造出来的、模组的药水没有预设名,效果只在自定义列表里,两处都读,不用维护白名单。
     */
    private static String brew(ItemStack stack) {
        var contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return "";
        }
        StringBuilder label = new StringBuilder();
        contents.potion().ifPresent(held -> label.append(path(held)));
        for (var effect : contents.customEffects()) {
            if (label.length() > 0) {
                label.append('+');
            }
            label.append(path(effect.getEffect()));
        }
        return label.toString();
    }
}
