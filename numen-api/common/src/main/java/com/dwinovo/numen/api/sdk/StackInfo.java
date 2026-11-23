package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.entity.Stacks;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Optional;

/**
 * 她背包里的一种东西,脚本里的类 {@code Stack}:物品与组件都相同的各叠合成的一项。要她带着的一样东西的参数收下它就是这一种
 * ({@link StackRef} 认它的 {@code item} 与 {@code kind})。
 */
@Doc("One kind of item in your backpack: every stack with the same item and the same components, counted together.")
public record StackInfo(@Doc("The item id, minecraft:cobblestone.") String item,
                        @Doc("How many you carry of this kind, all its stacks together.") int count,
                        @Doc("What tells this kind apart from the same item without it: a shulker box's contents, a "
                                + "custom name, enchantments, a potion's effect. Absent for a plain item.")
                        Optional<String> detail,
                        @Doc("The mark of this kind. Where a function takes an item you carry, pass this whole entry "
                                + "instead of the item id to use exactly this kind.") String kind) {

    /**
     * 背包里的一种写成模型眼里的样子。
     *
     * @param kind   这一种
     * @param detail 它的描述({@code StackLabel.details} 排过重的);没什么可说是空串
     */
    public static StackInfo of(Stacks.Kind kind, String detail) {
        return new StackInfo(BuiltInRegistries.ITEM.getKey(kind.sample().getItem()).toString(), kind.count(),
                detail.isEmpty() ? Optional.empty() : Optional.of(detail), Stacks.code(kind.sample()));
    }
}
