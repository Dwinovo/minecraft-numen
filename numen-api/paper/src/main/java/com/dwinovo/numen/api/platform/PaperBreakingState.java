package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.IBreakingState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Paper 上的 {@link IBreakingState}:Paper 的服务端是 Mojang 名,按名字取私有的记账字段,句柄一次查好缓存。
 * 找不到(换了版本改了名)是类加载时就抛的错,不是等到有人挖方块才发现。
 */
public final class PaperBreakingState implements IBreakingState {

    private static final VarHandle IS_DESTROYING_BLOCK;
    private static final VarHandle DESTROY_POS;
    private static final VarHandle HAS_DELAYED_DESTROY;
    private static final VarHandle DELAYED_DESTROY_POS;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ServerPlayerGameMode.class, MethodHandles.lookup());
            IS_DESTROYING_BLOCK = lookup.findVarHandle(ServerPlayerGameMode.class, "isDestroyingBlock", boolean.class);
            DESTROY_POS = lookup.findVarHandle(ServerPlayerGameMode.class, "destroyPos", BlockPos.class);
            HAS_DELAYED_DESTROY = lookup.findVarHandle(ServerPlayerGameMode.class, "hasDelayedDestroy", boolean.class);
            DELAYED_DESTROY_POS = lookup.findVarHandle(ServerPlayerGameMode.class, "delayedDestroyPos", BlockPos.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public boolean breaking(ServerPlayerGameMode mode, BlockPos pos) {
        return ((boolean) HAS_DELAYED_DESTROY.get(mode) && pos.equals((BlockPos) DELAYED_DESTROY_POS.get(mode)))
                || ((boolean) IS_DESTROYING_BLOCK.get(mode) && pos.equals((BlockPos) DESTROY_POS.get(mode)));
    }
}
