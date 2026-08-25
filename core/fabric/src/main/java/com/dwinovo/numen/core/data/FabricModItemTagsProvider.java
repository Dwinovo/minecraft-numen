package com.dwinovo.numen.core.data;

import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;
import net.minecraft.core.HolderLookup;

/**
 * Fabric-side item tag provider. Forwards to {@link ModItemTagData} so the
 * tag content lives in {@code common/} and stays loader-agnostic.
 */
public final class FabricModItemTagsProvider extends FabricTagsProvider.ItemTagsProvider {

    public FabricModItemTagsProvider(FabricPackOutput output,
                                     CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        ModItemTagData.addItemTags(key -> {
            // 26.2:值基 appender 删除,builder(key) 只收 ResourceKey——值→键在
            // 这层查注册表,common 的值基清单不动。
            var b = builder(key);
            // 与方块那侧同理:引用外部标签要跳过 Fabric 的 provider 归属校验
            return ModItemTagData.appender(
                    v -> b.add(net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getResourceKey(v).orElseThrow()),
                    t -> b.forceAddTag(t));
        });
    }
}
