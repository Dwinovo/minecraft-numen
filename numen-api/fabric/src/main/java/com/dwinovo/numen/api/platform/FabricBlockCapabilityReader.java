package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.BlockStorageReading;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.FluidTank;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.FluidTanks;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.ItemInventory;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.ItemSlot;
import com.dwinovo.numen.api.platform.services.Exposures;
import com.dwinovo.numen.api.platform.services.IBlockCapabilityReader;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric implementation of {@link IBlockCapabilityReader} — reads a block's item and fluid contents through the
 * Fabric Transfer API ({@code ItemStorage.SIDED} / {@code FluidStorage.SIDED}); a lookup with a {@code null} side is
 * allowed there and means the whole inventory, and {@link Exposures} also probes every face.
 *
 * <h2>Which lookups are the same storage</h2>
 * Transfer API storages are not one object per storage: a sided inventory gets a fresh wrapper per face and a
 * double chest a fresh combined storage on every query, and the API has no "same storage" test. {@link Exposures}
 * therefore compares the readings; the views are numbered in the order the storage iterates them, which for a
 * sided face is that face's own slots.
 *
 * <h2>Units</h2>
 * Fluid amounts are droplets ({@link FluidConstants#BUCKET} = 81000 per bucket) and are read as mB (1000 per bucket).
 *
 * <h2>Energy</h2>
 * fabric-api has no energy API (Team Reborn Energy is a separate library this mod does not depend on), so no energy
 * is reported here.
 */
public final class FabricBlockCapabilityReader implements IBlockCapabilityReader {

    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000;

    @Override
    public BlockStorageReading read(Level level, BlockPos pos) {
        return new BlockStorageReading(
                new Exposures<ItemInventory>().probe(d -> items(ItemStorage.SIDED.find(level, pos, d))).found(),
                new Exposures<FluidTanks>().probe(d -> fluids(FluidStorage.SIDED.find(level, pos, d))).found(),
                null);
    }

    private static ItemInventory items(Storage<ItemVariant> storage) {
        if (storage == null) return null;
        List<ItemSlot> slots = new ArrayList<>();
        for (StorageView<ItemVariant> view : storage) {
            slots.add(view.isResourceBlank() || view.getAmount() <= 0 ? ItemSlot.EMPTY
                    : new ItemSlot(BuiltInRegistries.ITEM.getKey(view.getResource().getItem()).toString(),
                            view.getAmount()));
        }
        return new ItemInventory(slots);
    }

    private static FluidTanks fluids(Storage<FluidVariant> storage) {
        if (storage == null) return null;
        List<FluidTank> tanks = new ArrayList<>();
        for (StorageView<FluidVariant> view : storage) {
            long capacity = view.getCapacity() / DROPLETS_PER_MB;
            tanks.add(view.isResourceBlank() || view.getAmount() <= 0 ? new FluidTank(null, 0, capacity)
                    : new FluidTank(BuiltInRegistries.FLUID.getKey(view.getResource().getFluid()).toString(),
                            view.getAmount() / DROPLETS_PER_MB, capacity));
        }
        return new FluidTanks(tanks);
    }
}
