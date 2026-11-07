package com.dwinovo.numen.api.mixin;

import com.dwinovo.numen.api.mixin.inject.GameModeAccessor;
import com.dwinovo.numen.api.platform.services.IBreakingState;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;

/** {@link IBreakingState} 的模组实现:经 {@link GameModeAccessor} 读原版私有的记账。 */
public final class BreakingStateImpl implements IBreakingState {

    @Override
    public boolean breaking(ServerPlayerGameMode mode, BlockPos pos) {
        GameModeAccessor state = (GameModeAccessor) mode;
        return (state.numen$hasDelayedDestroy() && pos.equals(state.numen$delayedDestroyPos()))
                || (state.numen$isDestroyingBlock() && pos.equals(state.numen$destroyPos()));
    }
}
