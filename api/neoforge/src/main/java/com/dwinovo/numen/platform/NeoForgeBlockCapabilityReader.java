package com.dwinovo.numen.platform;

import com.dwinovo.numen.platform.services.IBlockCapabilityReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * NeoForge implementation of {@link IBlockCapabilityReader} — reads a block's
 * item/fluid/energy contents through the standard block capabilities.
 *
 * <h2>Why query null AND every face</h2>
 * The {@code null} context means "no particular side"; many machines expose a
 * combined handler there, but several (Industrial Foregoing, Mekanism disabled
 * faces, Thermal side-config) return {@code null} for {@code null} and ONLY
 * expose per-face handlers. There is no documented contract that null-side is a
 * combined view, so we probe {@code null} + all six {@link Direction}s and
 * de-duplicate the returned handlers by identity, recording which sides exposed
 * each one.
 */
public final class NeoForgeBlockCapabilityReader implements IBlockCapabilityReader {

    @Override
    public List<String> describe(Level level, BlockPos pos) {
        List<String> out = new ArrayList<>();
        appendItems(level, pos, out);
        appendFluids(level, pos, out);
        appendEnergy(level, pos, out);
        return out;
    }

    private void appendItems(Level level, BlockPos pos, List<String> out) {
        Map<IItemHandler, List<String>> byHandler = new IdentityHashMap<>();
        collect(byHandler, level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null), "all");
        for (Direction d : Direction.values()) {
            collect(byHandler, level.getCapability(Capabilities.ItemHandler.BLOCK, pos, d), d.getName());
        }
        if (byHandler.isEmpty()) return;
        int idx = 0;
        for (Map.Entry<IItemHandler, List<String>> e : byHandler.entrySet()) {
            IItemHandler h = e.getKey();
            out.add("items" + (byHandler.size() > 1 ? " #" + idx : "") + " (sides: "
                    + String.join(",", e.getValue()) + "), " + h.getSlots() + " slots:");
            boolean any = false;
            for (int s = 0; s < h.getSlots(); s++) {
                ItemStack st = h.getStackInSlot(s);
                if (st.isEmpty()) continue;
                any = true;
                out.add("  slot " + s + ": " + itemId(st) + " x" + st.getCount());
            }
            if (!any) {
                out.add("  (all " + h.getSlots() + " slots empty)");
            }
            idx++;
        }
    }

    private void appendFluids(Level level, BlockPos pos, List<String> out) {
        Map<IFluidHandler, List<String>> byHandler = new IdentityHashMap<>();
        collect(byHandler, level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null), "all");
        for (Direction d : Direction.values()) {
            collect(byHandler, level.getCapability(Capabilities.FluidHandler.BLOCK, pos, d), d.getName());
        }
        if (byHandler.isEmpty()) return;
        int idx = 0;
        for (Map.Entry<IFluidHandler, List<String>> e : byHandler.entrySet()) {
            IFluidHandler h = e.getKey();
            out.add("fluids" + (byHandler.size() > 1 ? " #" + idx : "") + " (sides: "
                    + String.join(",", e.getValue()) + "):");
            for (int t = 0; t < h.getTanks(); t++) {
                FluidStack fs = h.getFluidInTank(t);
                out.add("  tank " + t + ": " + (fs.isEmpty() ? "empty" : fluidId(fs) + " " + fs.getAmount())
                        + "/" + h.getTankCapacity(t) + " mB");
            }
            idx++;
        }
    }

    private void appendEnergy(Level level, BlockPos pos, List<String> out) {
        IEnergyStorage en = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
        if (en == null) {
            for (Direction d : Direction.values()) {
                en = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, d);
                if (en != null) break;
            }
        }
        if (en == null) return;
        List<String> io = new ArrayList<>();
        if (en.canReceive()) io.add("accepts");
        if (en.canExtract()) io.add("provides");
        out.add("energy: " + en.getEnergyStored() + "/" + en.getMaxEnergyStored() + " FE"
                + (io.isEmpty() ? "" : " (" + String.join("/", io) + ")"));
    }

    /** Record a non-null handler under the side that exposed it, de-duplicating by identity. */
    private static <T> void collect(Map<T, List<String>> byHandler, T handler, String side) {
        if (handler == null) return;
        byHandler.computeIfAbsent(handler, h -> new ArrayList<>()).add(side);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String fluidId(FluidStack stack) {
        return BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString();
    }
}
