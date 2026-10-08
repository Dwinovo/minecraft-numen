package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.BlockStorageReading;
import com.dwinovo.numen.api.platform.services.IBlockCapabilityReader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/** Fabric implementation of {@link IBlockCapabilityReader}: not yet reading anything. */
public final class FabricBlockCapabilityReader implements IBlockCapabilityReader {

    @Override
    public BlockStorageReading read(Level level, BlockPos pos) {
        return new BlockStorageReading(List.of(), List.of(), null);
    }
}
