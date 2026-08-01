package com.dwinovo.numen.core.testutil;

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
 * <p><b>上下文必须带数据包注册表</b>:光给静态注册表
 * ({@code RegistryAccess.fromRegistryOfRegistries})不够——有物品的初始化器要查
 * {@code damage_type} 里的标签(如防火物品查 {@code minecraft:is_fire}),而
 * {@code damage_type} 是数据包注册表,静态注册表里根本没有,查不到就抛
 * {@code Missing tag},整个绑定连同 5 个测试类一起被 assumeTrue 静默跳过。
 * 改用 {@link VanillaRegistries#createLookup()}:它把静态注册表与全部数据包
 * 注册表的引导内容合成一份 provider,且对<b>任何</b>标签一律给空标签集
 * (RegistrySetBuilder 的 datagen 语义——标签来自数据文件,引导期本就没有)。
 * 这正是我们要的:标签查得到、内容为空,组件照常绑定,且不写死任何注册表清单。
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
