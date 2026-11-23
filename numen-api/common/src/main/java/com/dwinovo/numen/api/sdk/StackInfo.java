package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.entity.Stacks;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Map;

/**
 * 她背包里的一种东西,脚本里的类 {@code Stack}:物品与组件都相同的各叠合成的一项。要她带着的一样东西的参数收下它就是这一种
 * ({@link StackRef} 认它的 {@code item} 与 {@code kind})。
 */
@Doc("One kind of item in your backpack: every stack with the same item and the same components, counted together.")
public record StackInfo(@Doc("The item id, minecraft:cobblestone.") String item,
                        @Doc("How many you carry of this kind, all its stacks together.") int count,
                        @Doc("What tells this kind apart from a new item of its id, one named entry per kind of fact. "
                                + "Empty for a plain item.")
                        Map<String, Map<String, Object>> details,
                        @Doc("The mark of this kind. Where a function takes an item you carry, pass this whole entry "
                                + "instead of the item id to use exactly this kind.") String kind) {

    /**
     * 背包里的一种写成模型眼里的样子。
     *
     * @param kind    这一种
     * @param details 关于它回答者们说的({@code StackLabel.details}):回答者的名字到它说的字段;没人说话是空表
     */
    public static StackInfo of(Stacks.Kind kind, Map<String, Map<String, Object>> details) {
        return new StackInfo(BuiltInRegistries.ITEM.getKey(kind.sample().getItem()).toString(), kind.count(),
                details, Stacks.code(kind.sample()));
    }
}
