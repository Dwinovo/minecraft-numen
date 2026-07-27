package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.scaffold.ScaffoldItemCatalog;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NavSettings.class)
public abstract class NavSettingsScaffoldMixin {
    @Shadow private List<Item> acceptableThrowawayItems;

    @Inject(method = "acceptableThrowawayItems", at = @At("HEAD"), cancellable = true)
    private void numen$replaceScaffoldCandidates(CallbackInfoReturnable<List<Item>> callback) {
        if (this.acceptableThrowawayItems == null) {
            this.acceptableThrowawayItems = new ArrayList<>(ScaffoldItemCatalog.orderedStableItems());
        }
        callback.setReturnValue(this.acceptableThrowawayItems);
    }
}
