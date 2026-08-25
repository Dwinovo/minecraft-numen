package com.dwinovo.numen.core.data;

import com.dwinovo.numen.core.Constants;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.BlockTagsProvider;

/**
 * NeoForge-side block tag provider. Forwards to {@link ModBlockTagData} so tag
 * content stays loader-agnostic in {@code common/}.
 */
public final class ModBlockTagsProvider extends BlockTagsProvider {

    public ModBlockTagsProvider(PackOutput output,
                                CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, lookupProvider, Constants.MOD_ID);   // 1.21.4+ BlockTagsProvider has no EFH param
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        ModBlockTagData.addBlockTags(key -> {
            // 26.2:值基 appender(IntrinsicHolderTagsProvider)删除,tag(key) 只收
            // ResourceKey——值→键在这层查注册表,common 的值基清单不动。
            var b = tag(key);
            return ModItemTagData.appender(
                    v -> b.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getResourceKey(v).orElseThrow()),
                    t -> b.addTag(t));
        });
    }
}
