package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.program.ProgramHost;
import com.google.gson.JsonObject;

import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 只让客户端函数的反向请求经座位通过的世界({@link LoopbackTransport}):谁都坐得上座位,没有身体的样子可说,没有目录;
 * 程序与说话不是这个座位的事,要用就是用错了。
 */
public final class ClientCallsOnly implements SeatWorld {

    @Override
    public Executor main() {
        return Runnable::run;
    }

    @Override
    public boolean mayOpen(UUID player, UUID companion) {
        return true;
    }

    @Override
    public String state(UUID companion) {
        return "";
    }

    @Override
    public long gameTime() {
        return 0;
    }

    @Override
    public long dayTime() {
        return 0;
    }

    @Override
    public ProgramHost programs() {
        throw new UnsupportedOperationException("this seat only carries client calls; it does not run programs");
    }

    @Override
    public void say(UUID companion, String text) {
        throw new UnsupportedOperationException("this seat only carries client calls; it does not speak");
    }

    @Override
    public JsonObject catalog(UUID companion) {
        JsonObject catalog = new JsonObject();
        catalog.addProperty("version", "client-calls-only");
        return catalog;
    }
}
