package com.dwinovo.numen.core.data;

import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;
import net.minecraft.core.HolderLookup;

/**
 * Fabric-side block tag provider. Forwards to {@link ModBlockTagData} so the tag
 * content lives in {@code common/} and stays loader-agnostic.
 */
public final class FabricModBlockTagsProvider extends FabricTagsProvider.BlockTagsProvider {

    public FabricModBlockTagsProvider(FabricPackOutput output,
                                      CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        ModBlockTagData.addBlockTags(key -> {
            // 1.21.8:getOrCreateTagBuilder → valueLookupBuilder。
            var b = valueLookupBuilder(key);
            // 引用原版标签走 forceAddTag:Fabric 的 addTag 会校验"被引用的标签是不是
            // 这个 provider 自己定义过的",而 #minecraft:banners 显然不是,于是 datagen
            // 直接抛"missing following references"。NeoForge 那边没有这道校验,所以
            // 这是个只在 Fabric 上现形的错。forceAddTag 跳过校验,产物与 NeoForge 一致。
            return ModItemTagData.appender(v -> b.add(v), t -> b.forceAddTag(t));
        });
    }
}
