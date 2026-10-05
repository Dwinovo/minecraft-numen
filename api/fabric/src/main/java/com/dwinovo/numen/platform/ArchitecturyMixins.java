package com.dwinovo.numen.platform;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Fabric 上 {@code numen_api.fabric.architectury.mixins.json} 的挂法:Architectury 在场才交出注入它的那条 mixin
 * ({@code ArchitecturyPlayerHooksMixin}),不在场这份配置一条 mixin 都没有,也就不去找它的类。Fabric 的
 * {@code fabric.mod.json} 没有"某模组在场才挂"的写法,所以由这份配置的插件按加载器的模组清单答;NeoForge 上同一件事
 * 写在 {@code neoforge.mods.toml} 的 {@code requiredMods} 里。
 */
public final class ArchitecturyMixins implements IMixinConfigPlugin {

    @Override
    public List<String> getMixins() {
        return FabricLoader.getInstance().isModLoaded("architectury")
                ? List.of("ArchitecturyPlayerHooksMixin")
                : List.of();
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
