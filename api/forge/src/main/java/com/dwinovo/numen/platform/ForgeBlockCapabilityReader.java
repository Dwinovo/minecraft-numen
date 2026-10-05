package com.dwinovo.numen.platform;

import com.dwinovo.numen.platform.services.IBlockCapabilityReader;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forge (1.20.2) implementation of {@link IBlockCapabilityReader} — reads a
 * block's item/fluid/energy contents through the classic Forge capability
 * system.
 *
 * <h2>Forge vs NeoForge capability access</h2>
 * NeoForge's reworked system queries the level directly
 * ({@code level.getCapability(BlockCapability, pos, side)}); the classic Forge
 * system instead hangs capabilities off the {@link BlockEntity} and returns a
 * {@code LazyOptional}. So we fetch the block entity first and probe
 * {@code be.getCapability(cap, side)} for {@code null} + all six faces, since
 * many machines only expose per-face handlers. The handler interfaces
 * themselves ({@link IItemHandler}/{@link IFluidHandler}/{@link IEnergyStorage})
 * are identical in shape to the classic NeoForge ones, so the output format
 * matches the other loaders'.
 */
public final class ForgeBlockCapabilityReader implements IBlockCapabilityReader {

    @Override
    public List<String> describe(Level level, BlockPos pos) {
        List<String> out = new ArrayList<>();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return out; // Forge capabilities live on the block entity
        appendItems(be, out);
        appendFluids(be, out);
        appendEnergy(be, out);
        return out;
    }

    private void appendItems(BlockEntity be, List<String> out) {
        Map<IItemHandler, List<String>> byHandler = sided(be, ForgeCapabilities.ITEM_HANDLER);
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

    private void appendFluids(BlockEntity be, List<String> out) {
        Map<IFluidHandler, List<String>> byHandler = sided(be, ForgeCapabilities.FLUID_HANDLER);
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

    private void appendEnergy(BlockEntity be, List<String> out) {
        IEnergyStorage en = be.getCapability(ForgeCapabilities.ENERGY, null).orElse(null);
        if (en == null) {
            for (Direction d : Direction.values()) {
                en = be.getCapability(ForgeCapabilities.ENERGY, d).orElse(null);
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

    /**
     * Probe a capability on the {@code null} context plus all six faces, mapping
     * each distinct handler (by identity) to the sides that exposed it.
     */
    private static <T> Map<T, List<String>> sided(BlockEntity be, Capability<T> cap) {
        Map<T, List<String>> byHandler = new IdentityHashMap<>();
        collect(byHandler, be.getCapability(cap, null).orElse(null), "all");
        for (Direction d : Direction.values()) {
            collect(byHandler, be.getCapability(cap, d).orElse(null), d.getName());
        }
        return byHandler;
    }

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
