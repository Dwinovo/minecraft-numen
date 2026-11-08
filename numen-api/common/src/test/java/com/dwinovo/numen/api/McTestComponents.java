package com.dwinovo.numen.api;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * 每次跑单测先引导原版并绑定物品的数据组件,再开始任何测试类。26.1 起物品的数据组件不存在 Item 上,由数据包加载期的
 * {@code DataComponentInitializers} 绑到注册表引用上;只做 {@code Bootstrap.bootStrap()} 的话构造 {@code ItemStack} 就抛
 * "Components not bound yet"。这里用原版自己的管线(与服务器加载数据包同源)补绑一遍,上下文取
 * {@link VanillaRegistries#createWorldLookup()}:静态注册表加全部数据包注册表的引导内容,标签一律空集——只给静态注册表的话,
 * 防火物品的初始化器查 {@code minecraft:is_fire} 伤害类型标签就抛出。经 {@code META-INF/services} 登记给 JUnit。
 */
public final class McTestComponents implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var provider = VanillaRegistries.createWorldLookup();
        for (DataComponentInitializers.PendingComponents<?> pending
                : BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(provider)) {
            pending.apply();
        }
    }
}
