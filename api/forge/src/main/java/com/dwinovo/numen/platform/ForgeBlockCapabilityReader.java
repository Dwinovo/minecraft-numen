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
 * Forge (1.20.4) implementation of {@link IBlockCapabilityReader} — reads a
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

    /** Cap on listed non-empty item slots per handler, so a huge modded inventory can't blow up the reply. */
    private static final int MAX_SLOT_LINES = 64;

    @Override
    public String describe(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null; // Forge capabilities live on the block entity
        StringBuilder sb = new StringBuilder();
        appendItems(be, sb);
        appendFluids(be, sb);
        appendEnergy(be, sb);
        return sb.length() == 0 ? null : sb.toString();
    }

    private void appendItems(BlockEntity be, StringBuilder sb) {
        Map<IItemHandler, List<String>> byHandler = sided(be, ForgeCapabilities.ITEM_HANDLER);
        if (byHandler.isEmpty()) return;
        int idx = 0;
        for (Map.Entry<IItemHandler, List<String>> e : byHandler.entrySet()) {
            IItemHandler h = e.getKey();
            sb.append("items").append(byHandler.size() > 1 ? " #" + idx : "")
                    .append(" (sides: ").append(String.join(",", e.getValue())).append("), ")
                    .append(h.getSlots()).append(" slots:\n");
            int shown = 0;
            boolean any = false;
            for (int s = 0; s < h.getSlots(); s++) {
                ItemStack st = h.getStackInSlot(s);
                if (st.isEmpty()) continue;
                any = true;
                if (shown++ >= MAX_SLOT_LINES) continue;
                sb.append("  slot ").append(s).append(": ")
                        .append(itemId(st)).append(" x").append(st.getCount()).append("\n");
            }
            if (!any) {
                sb.append("  (all ").append(h.getSlots()).append(" slots empty)\n");
            } else if (shown > MAX_SLOT_LINES) {
                sb.append("  … and ").append(shown - MAX_SLOT_LINES).append(" more non-empty slots\n");
            }
            idx++;
        }
    }

    private void appendFluids(BlockEntity be, StringBuilder sb) {
        Map<IFluidHandler, List<String>> byHandler = sided(be, ForgeCapabilities.FLUID_HANDLER);
        if (byHandler.isEmpty()) return;
        int idx = 0;
        for (Map.Entry<IFluidHandler, List<String>> e : byHandler.entrySet()) {
            IFluidHandler h = e.getKey();
            sb.append("fluids").append(byHandler.size() > 1 ? " #" + idx : "")
                    .append(" (sides: ").append(String.join(",", e.getValue())).append("):\n");
            for (int t = 0; t < h.getTanks(); t++) {
                FluidStack fs = h.getFluidInTank(t);
                sb.append("  tank ").append(t).append(": ");
                if (fs.isEmpty()) {
                    sb.append("empty");
                } else {
                    sb.append(fluidId(fs)).append(" ").append(fs.getAmount());
                }
                sb.append("/").append(h.getTankCapacity(t)).append(" mB\n");
            }
            idx++;
        }
    }

    private void appendEnergy(BlockEntity be, StringBuilder sb) {
        IEnergyStorage en = be.getCapability(ForgeCapabilities.ENERGY, null).orElse(null);
        if (en == null) {
            for (Direction d : Direction.values()) {
                en = be.getCapability(ForgeCapabilities.ENERGY, d).orElse(null);
                if (en != null) break;
            }
        }
        if (en == null) return;
        sb.append("energy: ").append(en.getEnergyStored()).append("/").append(en.getMaxEnergyStored())
                .append(" FE");
        List<String> io = new ArrayList<>();
        if (en.canReceive()) io.add("accepts");
        if (en.canExtract()) io.add("provides");
        if (!io.isEmpty()) sb.append(" (").append(String.join("/", io)).append(")");
        sb.append("\n");
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
