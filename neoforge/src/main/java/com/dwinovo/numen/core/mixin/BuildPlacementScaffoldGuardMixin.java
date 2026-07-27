package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.mining.ActiveMiningTargets;
import com.dwinovo.numen.core.pathing.moves.movements.BuildPlacementRegistry;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BuildPlacementRegistry.class)
public abstract class BuildPlacementScaffoldGuardMixin {
    @Redirect(
        method = "selectGenericThrowaway",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dwinovo/numen/core/pathing/settings/NavSettings;"
                + "acceptableThrowawayItems()Ljava/util/List;"
        ),
        require = 1
    )
    private static List<Item> numen$excludeActiveMiningTargets(
        NavSettings settings,
        ServerPlayer player,
        BlockHitResult hit,
        float yaw,
        float pitch,
        boolean select
    ) {
        List<Item> original = settings.acceptableThrowawayItems();
        if (!(player instanceof NumenPlayer companion)) {
            return original;
        }

        Set<String> targetIds = ActiveMiningTargets.ids(companion.getUUID());
        if (original == null || original.isEmpty() || targetIds.isEmpty()) {
            return original;
        }

        List<Item> filtered = new ArrayList<>();
        for (Item item : original) {
            if (item instanceof BlockItem blockItem) {
                String itemId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
                if (targetIds.contains(itemId)) {
                    continue;
                }
            }
            filtered.add(item);
        }
        return filtered.size() == original.size() ? original : List.copyOf(filtered);
    }
}
