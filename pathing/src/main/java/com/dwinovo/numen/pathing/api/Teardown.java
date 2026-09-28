package com.dwinovo.numen.pathing.api;

import java.util.List;

import com.dwinovo.numen.pathing.drive.TakeBack;

import net.minecraft.core.BlockPos;

/**
 * 一次在做的撤回:把路上垫下的方块照原版挖掉(见 {@link TakeBack})。宿主每个服务端刻调一次 {@link #tick},直到它答否;
 * 撤掉了哪几格、哪几块留在原处以及为什么,是 {@link #taken} 与 {@link #left};实际账照样交出。
 */
public final class Teardown {

    private final TakeBack takeBack;

    Teardown(TakeBack takeBack) {
        this.takeBack = takeBack;
    }

    /** 推一刻;还在撤为 true。 */
    public boolean tick() {
        return takeBack.tick() == TakeBack.State.RUNNING;
    }

    /** 撤掉了的格,按先后。 */
    public List<BlockPos> taken() {
        return takeBack.taken();
    }

    /** 留在原处的块。 */
    public List<TakeBack.Left> left() {
        return takeBack.left();
    }

    /** 叫停:松开所有键,交出实际账;没撤的块既不在 {@link #taken} 也不在 {@link #left} 里。 */
    public Report stop() {
        takeBack.stop();
        return report();
    }

    /** 排障用:还剩哪几块;经过见日志。 */
    @Override
    public String toString() {
        return "Teardown[" + takeBack + "]";
    }

    /** 到此刻为止的实际账:挖掉的垫块,以及身体为此做的动作(换工具)。 */
    public Report report() {
        return new Report(takeBack.ledger(), takeBack.actions());
    }
}
