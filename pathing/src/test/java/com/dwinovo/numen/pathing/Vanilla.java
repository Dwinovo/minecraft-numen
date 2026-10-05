package com.dwinovo.numen.pathing;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.entity.EntityDimensions;

import com.dwinovo.numen.pathing.world.BodyStats;

/**
 * 单测的原版环境:引导注册表,再用原版自己的标签加载器从原版数据包读出方块与流体标签绑上——{@code Bootstrap} 只建注册表、
 * 不加载数据包,不绑的话 {@code #minecraft:climbable}、{@code #minecraft:fire}、水与岩浆的流体标签都是空的。读的是类路径上
 * 原版 jar 里的数据,与服务器启动时读的是同一份。引导或读标签失败就抛出,测试随之失败,不跳过。
 */
public final class Vanilla {

    /** 原版玩家的身体:宽 0.6、站立高 1.8 眼高 1.62、潜行高 1.5 眼高 1.27;迈步 0.6、起跳 0.42、重力 0.08;生存交互距离 4.5。 */
    public static final BodyStats SURVIVAL;
    /** 同一具身体在创造模式下:交互距离 5。 */
    public static final BodyStats CREATIVE;
    /** 同一具生存模式的身体穿上皮靴:细雪托得住它。 */
    public static final BodyStats LEATHER_BOOTS;
    /** 同一具生存模式的身体穿上带冰霜行者的靴子:静水面冻得住。 */
    public static final BodyStats FROST_WALKER;

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (MultiPackResourceManager data = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource()))) {
            bindTags(BuiltInRegistries.BLOCK, data);
            bindTags(BuiltInRegistries.FLUID, data);
        }
        EntityDimensions standing = EntityDimensions.scalable(0.6F, 1.8F).withEyeHeight(1.62F);
        EntityDimensions crouching = EntityDimensions.scalable(0.6F, 1.5F).withEyeHeight(1.27F);
        SURVIVAL = new BodyStats(standing, crouching, 0.6, 0.42F, 0.08, 4.5, false, false);
        CREATIVE = new BodyStats(standing, crouching, 0.6, 0.42F, 0.08, 5.0, false, false);
        LEATHER_BOOTS = new BodyStats(standing, crouching, 0.6, 0.42F, 0.08, 4.5, true, false);
        FROST_WALKER = new BodyStats(standing, crouching, 0.6, 0.42F, 0.08, 4.5, false, true);
    }

    private Vanilla() {}

    /** 触发引导。各测试类在 {@code @BeforeAll} 里调;引导只在第一次发生。 */
    public static void boot() {
        // 类初始化即引导
    }

    @SuppressWarnings("unchecked")
    private static <T> void bindTags(Registry<T> registry, ResourceManager data) {
        TagLoader<Holder<T>> loader = new TagLoader<>(
                id -> registry.getHolder(ResourceKey.create(registry.key(), id)),
                Registries.tagsDirPath(registry.key()));
        Map<ResourceLocation, Collection<Holder<T>>> loaded = loader.loadAndBuild(data);
        if (loaded.isEmpty()) {
            throw new IllegalStateException("没有从原版数据包读到 " + registry.key() + " 的标签");
        }
        Map<TagKey<T>, List<Holder<T>>> tags = new HashMap<>();
        loaded.forEach((id, members) -> tags.put(TagKey.create(registry.key(), id), List.copyOf(members)));
        ((MappedRegistry<T>) registry).bindTags(tags);
    }
}
