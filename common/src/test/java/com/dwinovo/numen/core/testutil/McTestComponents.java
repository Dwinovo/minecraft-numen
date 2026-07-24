package com.dwinovo.numen.core.testutil;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 26.1 起物品的 DataComponentMap 不再存在 Item 上(Item.components() 反过来委托
 * holder),而是由数据包加载期的 DataComponentInitializers 绑定到 Holder.Reference;
 * headless 引导(仅 Bootstrap.bootStrap())不会走这一步,ItemStack 构造会抛
 * "Components not bound yet"。测试引导后调用一次本方法,用 vanilla 自己的管线
 * (DATA_COMPONENT_INITIALIZERS.build(...).apply(),与 ReloadableServerResources
 * 同源)在静态注册表上补绑一遍。幂等:重复调用由 areComponentsBound 幸免。
 */
public final class McTestComponents {

    private McTestComponents() {}

    private static volatile boolean bound;

    public static void bindAll() {
        if (bound) {
            return;
        }
        var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for (DataComponentInitializers.PendingComponents<?> pending
                : BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(provider)) {
            pending.apply();
        }
        bound = true;
    }
}
