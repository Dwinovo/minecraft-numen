package com.dwinovo.numen.core.scaffold;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Builds the ordered item list consumed by Numen's generic placement code. */
public final class ScaffoldItemCatalog {
    private static volatile List<Item> cached;

    private ScaffoldItemCatalog() {
    }

    public static List<Item> orderedStableItems() {
        List<Item> existing = cached;
        if (existing != null) {
            return existing;
        }
        synchronized (ScaffoldItemCatalog.class) {
            if (cached == null) {
                cached = buildOrderedStableItems();
            }
            return cached;
        }
    }

    private static List<Item> buildOrderedStableItems() {
        Map<String, Item> byId = new LinkedHashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof BlockItem blockItem)) {
                continue;
            }

            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (ScaffoldMaterialPolicy.isAbsolutelyForbidden(id)
                || isKnownHazard(id)
                || !isStableSupport(blockItem.getBlock())) {
                continue;
            }
            byId.put(id, item);
        }

        return ScaffoldMaterialPolicy.orderUsableIds(byId.keySet()).stream()
            .map(byId::get)
            .toList();
    }

    private static boolean isKnownHazard(String id) {
        return id.equals("minecraft:magma_block")
            || id.equals("minecraft:powder_snow");
    }

    private static boolean isStableSupport(Block block) {
        if (block instanceof FallingBlock || block instanceof EntityBlock) {
            return false;
        }

        try {
            BlockState state = block.defaultBlockState();
            return !state.isAir()
                && state.getFluidState().isEmpty()
                && state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) >= 0.0F
                && Block.isShapeFullBlock(
                    state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                );
        } catch (RuntimeException ignored) {
            // A modded block that cannot describe itself without a live level is not safe scaffolding.
            return false;
        }
    }
}
