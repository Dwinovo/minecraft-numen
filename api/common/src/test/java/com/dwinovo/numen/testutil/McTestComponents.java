package com.dwinovo.numen.testutil;

import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;

/**
 * 26.1 起物品的 DataComponentMap 不再存在 Item 上(Item.components() 反过来委托
 * holder),而是由数据包加载期的 DataComponentInitializers 绑定到 Holder.Reference;
 * headless 引导(仅 Bootstrap.bootStrap())不会走这一步,ItemStack 构造会抛
 * "Components not bound yet"。测试引导后调用一次本方法,用 vanilla 自己的管线
 * (DATA_COMPONENT_INITIALIZERS.build(...).apply(),与 ReloadableServerResources
 * 同源)在静态注册表上补绑一遍。幂等:重复调用由 bound 挡住。
 *
 * <p>上下文用 {@link VanillaRegistries#createLookup()}——静态注册表 + 全部数据包
 * 注册表的引导内容,任何标签一律空集,组件照常绑定(判据详见 core 测试源码集的
 * 同名拷贝:测试夹具跨模块不可见,只能各持一份)。
 */
public final class McTestComponents {

    private McTestComponents() {}

    private static volatile boolean bound;

    public static void bindAll() {
        if (bound) {
            return;
        }
        var provider = VanillaRegistries.createLookup();
        for (DataComponentInitializers.PendingComponents<?> pending
                : BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(provider)) {
            pending.apply();
        }
        bound = true;
    }
}
