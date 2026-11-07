package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.BlockStorageReading;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.Energy;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.FluidTank;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.FluidTanks;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.ItemInventory;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.ItemSlot;
import com.dwinovo.numen.api.platform.services.Exposures;
import com.dwinovo.numen.api.platform.services.IBlockCapabilityReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.ArrayList;
import java.util.List;

/**
 * NeoForge implementation of {@link IBlockCapabilityReader} — reads a block's
 * item/fluid/energy contents through the standard block capabilities.
 *
 * <h2>Transfer API</h2>
 * Items and fluids are both {@link ResourceHandler} (slot-indexed {@code size()}/{@code getResource}/
 * {@code getAmountAsLong}), energy is {@link EnergyHandler}. Energy has no {@code canReceive()}/{@code canExtract()}
 * flags, so its direction is probed by simulating an insert/extract inside a {@link Transaction} that is never committed.
 *
 * <h2>Why query null AND every face</h2>
 * The {@code null} context means "no particular side"; many machines expose a
 * combined handler there, but several (Industrial Foregoing, Mekanism disabled
 * faces, Thermal side-config) return {@code null} for {@code null} and ONLY
 * expose per-face handlers. There is no documented contract that null-side is a
 * combined view, so we probe {@code null} + all six {@link Direction}s
 * ({@link Exposures}, which also decides what counts as the same storage).
 *
 * <h2>Transfer API</h2>
 * Items and fluids are both {@link ResourceHandler} (slot-indexed {@code size()} / {@code getResource} /
 * {@code getAmountAsLong}), energy is {@link EnergyHandler}. The energy handler has no
 * {@code canReceive()}/{@code canExtract()} flags, so its direction is probed by simulating an insert and
 * an extract inside a transaction that is never committed: the block's state is untouched.
 */
public final class NeoForgeBlockCapabilityReader implements IBlockCapabilityReader {

    @Override
    public BlockStorageReading read(Level level, BlockPos pos) {
        return new BlockStorageReading(
                new Exposures<ItemInventory>()
                        .probe(d -> items(level.getCapability(Capabilities.Item.BLOCK, pos, d))).found(),
                new Exposures<FluidTanks>()
                        .probe(d -> fluids(level.getCapability(Capabilities.Fluid.BLOCK, pos, d))).found(),
                energy(level, pos));
    }

    private static ItemInventory items(ResourceHandler<ItemResource> handler) {
        if (handler == null) return null;
        List<ItemSlot> slots = new ArrayList<>();
        for (int s = 0; s < handler.size(); s++) {
            ItemResource res = handler.getResource(s);
            slots.add(res.isEmpty() ? ItemSlot.EMPTY
                    : new ItemSlot(BuiltInRegistries.ITEM.getKey(res.getItem()).toString(), handler.getAmountAsLong(s)));
        }
        return new ItemInventory(slots);
    }

    private static FluidTanks fluids(ResourceHandler<FluidResource> handler) {
        if (handler == null) return null;
        List<FluidTank> tanks = new ArrayList<>();
        for (int t = 0; t < handler.size(); t++) {
            FluidResource res = handler.getResource(t);
            long capacity = handler.getCapacityAsLong(t, res);
            tanks.add(res.isEmpty()
                    ? new FluidTank(null, 0, capacity)
                    : new FluidTank(BuiltInRegistries.FLUID.getKey(res.getFluid()).toString(), handler.getAmountAsLong(t),
                            capacity));
        }
        return new FluidTanks(tanks);
    }

    /** 能量取第一个露出的:先不指定面,再逐面。 */
    private static Energy energy(Level level, BlockPos pos) {
        EnergyHandler en = level.getCapability(Capabilities.Energy.BLOCK, pos, null);
        for (Direction d : Direction.values()) {
            if (en != null) break;
            en = level.getCapability(Capabilities.Energy.BLOCK, pos, d);
        }
        if (en == null) return null;
        boolean accepts;
        boolean provides;
        // 没有 canReceive/canExtract:在永不提交的事务里模拟一次最大量的灌入、抽出来探方向,方块状态不受影响
        try (Transaction tx = Transaction.openRoot()) {
            accepts = en.insert(Integer.MAX_VALUE, tx) > 0;
            provides = en.extract(Integer.MAX_VALUE, tx) > 0;
        }
        return new Energy(en.getAmountAsLong(), en.getCapacityAsLong(), accepts, provides);
    }
}
