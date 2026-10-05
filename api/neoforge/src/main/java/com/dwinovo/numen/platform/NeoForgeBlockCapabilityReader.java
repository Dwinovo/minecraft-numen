package com.dwinovo.numen.platform;

import com.dwinovo.numen.platform.services.IBlockCapabilityReader;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * NeoForge implementation of {@link IBlockCapabilityReader} — reads a block's
 * item/fluid/energy contents through the standard block capabilities.
 *
 * <h2>Transfer Rework (MC 1.21.9+)</h2>
 * This branch targets the reworked transfer API: items and fluids are both
 * {@link ResourceHandler} (slot-indexed {@code size()}/{@code getResource}/
 * {@code getAmountAsLong}), and energy is {@link EnergyHandler}. The old
 * {@code canReceive()}/{@code canExtract()} flags are gone, so we probe energy
 * direction by simulating an insert/extract inside a rolled-back
 * {@link Transaction}. The pre-1.21.9 branches use the classic
 * {@code IItemHandler}/{@code IFluidHandler}/{@code IEnergyStorage} variant.
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
        Map<ResourceHandler<ItemResource>, List<String>> byHandler = new IdentityHashMap<>();
        collect(byHandler, level.getCapability(Capabilities.Item.BLOCK, pos, null), "all");
        for (Direction d : Direction.values()) {
            collect(byHandler, level.getCapability(Capabilities.Item.BLOCK, pos, d), d.getName());
        }
        if (byHandler.isEmpty()) return;
        int idx = 0;
        for (Map.Entry<ResourceHandler<ItemResource>, List<String>> e : byHandler.entrySet()) {
            ResourceHandler<ItemResource> h = e.getKey();
            out.add("items" + (byHandler.size() > 1 ? " #" + idx : "") + " (sides: "
                    + String.join(",", e.getValue()) + "), " + h.size() + " slots:");
            boolean any = false;
            for (int s = 0; s < h.size(); s++) {
                ItemResource res = h.getResource(s);
                if (res.isEmpty()) continue;
                any = true;
                out.add("  slot " + s + ": " + itemId(res) + " x" + h.getAmountAsLong(s));
            }
            if (!any) {
                out.add("  (all " + h.size() + " slots empty)");
            }
            idx++;
        }
    }

    private void appendFluids(Level level, BlockPos pos, List<String> out) {
        Map<ResourceHandler<FluidResource>, List<String>> byHandler = new IdentityHashMap<>();
        collect(byHandler, level.getCapability(Capabilities.Fluid.BLOCK, pos, null), "all");
        for (Direction d : Direction.values()) {
            collect(byHandler, level.getCapability(Capabilities.Fluid.BLOCK, pos, d), d.getName());
        }
        if (byHandler.isEmpty()) return;
        int idx = 0;
        for (Map.Entry<ResourceHandler<FluidResource>, List<String>> e : byHandler.entrySet()) {
            ResourceHandler<FluidResource> h = e.getKey();
            out.add("fluids" + (byHandler.size() > 1 ? " #" + idx : "") + " (sides: "
                    + String.join(",", e.getValue()) + "):");
            for (int t = 0; t < h.size(); t++) {
                FluidResource res = h.getResource(t);
                out.add("  tank " + t + ": " + (res.isEmpty() ? "empty" : fluidId(res) + " " + h.getAmountAsLong(t))
                        + "/" + h.getCapacityAsLong(t, res) + " mB");
            }
            idx++;
        }
    }

    private void appendEnergy(Level level, BlockPos pos, List<String> out) {
        EnergyHandler en = level.getCapability(Capabilities.Energy.BLOCK, pos, null);
        if (en == null) {
            for (Direction d : Direction.values()) {
                en = level.getCapability(Capabilities.Energy.BLOCK, pos, d);
                if (en != null) break;
            }
        }
        if (en == null) return;
        List<String> io = new ArrayList<>();
        // 重写后的 EnergyHandler 没有 canReceive()/canExtract():在永不提交的事务里
        // 模拟一次最大量的灌入/抽出来探方向,方块状态不受影响。
        try (Transaction tx = Transaction.openRoot()) {
            if (en.insert(Integer.MAX_VALUE, tx) > 0) io.add("accepts");
            if (en.extract(Integer.MAX_VALUE, tx) > 0) io.add("provides");
        }
        out.add("energy: " + en.getAmountAsLong() + "/" + en.getCapacityAsLong() + " FE"
                + (io.isEmpty() ? "" : " (" + String.join("/", io) + ")"));
    }

    /** Record a non-null handler under the side that exposed it, de-duplicating by identity. */
    private static <T> void collect(Map<T, List<String>> byHandler, T handler, String side) {
        if (handler == null) return;
        byHandler.computeIfAbsent(handler, h -> new ArrayList<>()).add(side);
    }

    private static String itemId(ItemResource res) {
        return BuiltInRegistries.ITEM.getKey(res.getItem()).toString();
    }

    private static String fluidId(FluidResource res) {
        return BuiltInRegistries.FLUID.getKey(res.getFluid()).toString();
    }
}
