package com.dwinovo.numen.api.platform.services;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Reads the item / fluid / energy a block <em>holds</em>, through the loader's
 * standard storage system — NeoForge's {@code IItemHandler} /
 * {@code IFluidHandler} / {@code IEnergyStorage} block capabilities, or Fabric's
 * Transfer API.
 *
 * <h2>Why a service</h2>
 * This is the companion's "eyes" for machines/tanks/batteries. The storage
 * system is the modded ecosystem's universal interop contract (pipes, hoppers
 * and funnels all read machines through it), so a single reader perceives the
 * contents of the vast majority of modded machines <strong>without opening any
 * GUI</strong> and <strong>without per-mod code</strong>. But the storage
 * types are loader-specific, so the reading itself lives behind this platform
 * service; {@code common} stays loader-neutral.
 *
 * <h2>Contract</h2>
 * Implementations query the block at {@code pos} on the "no particular side" context and on every face
 * (some machines only expose per-side), merge what is the same storage (see {@link Exposures}) and hand back the
 * numbers as a {@link BlockStorageReading} — they do not word anything: {@link BlockStorageReading#lines()} is the
 * one place that writes the text for the model. Fluids are in mB. The reading is empty when the block exposes no
 * standard storage on any side (a decorative/menu-only block, or a kind of storage the loader has no standard
 * for). A storage-network terminal (AE2/RS) will show only its local buffer here, never the whole network.
 */
public interface IBlockCapabilityReader {

    BlockStorageReading read(Level level, BlockPos pos);
}
