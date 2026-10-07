package com.dwinovo.numen.platform.services;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Reads the item / fluid / energy a block <em>holds</em>, through the loader's
 * standard capability system — NeoForge's {@code IItemHandler} /
 * {@code IFluidHandler} / {@code IEnergyStorage} block capabilities, or (later)
 * Fabric's Transfer API.
 *
 * <h2>Why a service</h2>
 * This is the companion's "eyes" for machines/tanks/batteries. The capability
 * system is the modded ecosystem's universal interop contract (pipes, hoppers
 * and funnels all read machines through it), so a single reader perceives the
 * contents of the vast majority of modded machines <strong>without opening any
 * GUI</strong> and <strong>without per-mod code</strong>. But the capability
 * types are loader-specific, so the reading itself lives behind this platform
 * service; {@code common} stays loader-neutral and gets back a formatted summary.
 *
 * <h2>Contract</h2>
 * Implementations query the block at {@code pos} for each standard handler on
 * the {@code null} context and on every face (some machines only expose
 * per-side), de-duplicate handlers by identity, and format what they find.
 *
 * <p>{@link #describe} returns the LLM-facing lines of what it found — a heading
 * per handler, one line per non-empty item slot, per tank, and the energy — every
 * one of them; the caller pages them by the output budget. It is empty when the
 * block exposes no standard storage/energy capability on any side (a
 * decorative/menu-only block, or a loader with no implementation yet). A storage-network terminal (AE2/RS) will
 * show only its local buffer here, never the whole network — that needs the
 * dedicated network reader (T3).
 */
public interface IBlockCapabilityReader {

    List<String> describe(Level level, BlockPos pos);
}
