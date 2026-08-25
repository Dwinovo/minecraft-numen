package com.dwinovo.numen.core.data;

import com.dwinovo.numen.core.Constants;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.ItemTagsProvider;

/**
 * NeoForge-side item tag provider. Forwards to {@link ModItemTagData} so tag
 * content stays loader-agnostic in {@code common/}.
 *
 * <p>1.21.8 exposes {@code net.neoforged.neoforge.common.data.ItemTagsProvider}
 * (a simple 3-arg {@code output, lookup, modId} form), so we extend it directly
 * instead of the vanilla provider + empty block-tag lookup the older targets needed.
 */
public final class ModItemTagsProvider extends ItemTagsProvider {

    public ModItemTagsProvider(PackOutput output,
                               CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, lookupProvider, Constants.MOD_ID);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        ModItemTagData.addItemTags(key -> {
            // 26.2:值基 appender(IntrinsicHolderTagsProvider)删除,tag(key) 只收
            // ResourceKey——值→键在这层查注册表,common 的值基清单不动。
            var b = tag(key);
            return ModItemTagData.appender(
                    v -> b.add(net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getResourceKey(v).orElseThrow()),
                    t -> b.addTag(t));
        });
    }
}
