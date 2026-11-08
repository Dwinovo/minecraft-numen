package com.dwinovo.numen.data;

import com.dwinovo.numen.Constants;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/**
 * Forge-side item tag provider. Forwards to {@link ModItemTagData} so tag content stays loader-agnostic in
 * {@code common/}. The vanilla provider can copy block tags into item tags via a block-tag lookup; we copy none, so an
 * empty lookup is fed.
 */
public final class ModItemTagsProvider extends ItemTagsProvider {

    public ModItemTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider,
                               ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, CompletableFuture.completedFuture(TagsProvider.TagLookup.empty()),
                Constants.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        ModItemTagData.addItemTags(key -> {
            var b = tag(key);
            return ModItemTagData.appender(v -> b.add(v), t -> b.addTag(t));
        });
    }
}
