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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge implementation of {@link IBlockCapabilityReader} — reads a block's
 * item/fluid/energy contents through the standard block capabilities.
 *
 * <h2>Why query null AND every face</h2>
 * The {@code null} context means "no particular side"; many machines expose a
 * combined handler there, but several (Industrial Foregoing, Mekanism disabled
 * faces, Thermal side-config) return {@code null} for {@code null} and ONLY
 * expose per-face handlers. There is no documented contract that null-side is a
 * combined view, so we probe {@code null} + all six {@link Direction}s
 * ({@link Exposures}, which also decides what counts as the same storage).
 */
public final class ForgeBlockCapabilityReader implements IBlockCapabilityReader {

    @Override
    public BlockStorageReading read(Level level, BlockPos pos) {
        return new BlockStorageReading(
                new Exposures<ItemInventory>()
                        .probe(d -> items(capability(level, pos, ForgeCapabilities.ITEM_HANDLER, d))).found(),
                new Exposures<FluidTanks>()
                        .probe(d -> fluids(capability(level, pos, ForgeCapabilities.FLUID_HANDLER, d))).found(),
                energy(level, pos));
    }

    /** Forge 的能力挂在方块实体上:没有方块实体就没有能力。 */
    private static <T> T capability(Level level, BlockPos pos, Capability<T> cap, Direction side) {
        BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : be.getCapability(cap, side).orElse(null);
    }

    private static ItemInventory items(IItemHandler handler) {
        if (handler == null) return null;
        List<ItemSlot> slots = new ArrayList<>();
        for (int s = 0; s < handler.getSlots(); s++) {
            ItemStack stack = handler.getStackInSlot(s);
            slots.add(stack.isEmpty() ? ItemSlot.EMPTY
                    : new ItemSlot(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
        }
        return new ItemInventory(slots);
    }

    private static FluidTanks fluids(IFluidHandler handler) {
        if (handler == null) return null;
        List<FluidTank> tanks = new ArrayList<>();
        for (int t = 0; t < handler.getTanks(); t++) {
            FluidStack stack = handler.getFluidInTank(t);
            tanks.add(stack.isEmpty()
                    ? new FluidTank(null, 0, handler.getTankCapacity(t))
                    : new FluidTank(BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString(), stack.getAmount(),
                            handler.getTankCapacity(t)));
        }
        return new FluidTanks(tanks);
    }

    /** 能量取第一个露出的:先不指定面,再逐面。 */
    private static Energy energy(Level level, BlockPos pos) {
        IEnergyStorage en = capability(level, pos, ForgeCapabilities.ENERGY, null);
        for (Direction d : Direction.values()) {
            if (en != null) break;
            en = capability(level, pos, ForgeCapabilities.ENERGY, d);
        }
        return en == null ? null
                : new Energy(en.getEnergyStored(), en.getMaxEnergyStored(), en.canReceive(), en.canExtract());
    }
}
