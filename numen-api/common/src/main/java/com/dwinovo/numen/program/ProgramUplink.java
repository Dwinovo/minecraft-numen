package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.script.Program;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.network.payload.CancelTasksPayload;
import com.dwinovo.numen.network.payload.RunProgramPayload;
import com.dwinovo.numen.network.payload.StopProgramPayload;
import com.dwinovo.numen.script.Modules;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 主人客户端把一整段程序送去服务端跑、等它那一份答复的地方。发出去的程序按编号记着,答复({@link RunResult})回来交给等的一方;
 * 服务端说缺某些模块正文({@link RunResult.Missing})就把它们带上再送一次,不惊动等的一方。上行的出口是一个挂点
 * ({@code uplink}):主人客户端上是网络,评测换成直接交给服务端的入口。
 *
 * <p>线程:都在主人客户端的主线程上。
 */
public final class ProgramUplink {

    /**
     * 上行的出口:主人客户端上是网络({@link NumenNetwork#sendToServer})。没有主人客户端的进程(评测)换成把包交给服务端真实入口的
     * 那一个,同样经过包的编解码。写法同下行的 {@code ClientPayloadSink}:主源码集里一个静态挂点。
     */
    public static volatile Consumer<CustomPacketPayload> wire = NumenNetwork::sendToServer;

    /** 主人客户端上这个连接的那一份。 */
    public static final ProgramUplink CONNECTION = new ProgramUplink(new ModuleSync(), payload -> wire.accept(payload),
            Modules::of);

    private record Waiting(UUID companion, String code, Consumer<RunResult> done, boolean retried) {}

    private final ModuleSync sync;
    private final Consumer<CustomPacketPayload> uplink;
    /** 她的模块在哪:主人客户端上是 {@link Modules#of}。 */
    private final Function<UUID, Modules> modules;
    private final Map<String, Waiting> waiting = new ConcurrentHashMap<>();

    public ProgramUplink(ModuleSync sync, Consumer<CustomPacketPayload> uplink, Function<UUID, Modules> modules) {
        this.sync = sync;
        this.uplink = uplink;
        this.modules = modules;
    }

    /** 这个连接上送过哪些模块正文。 */
    public ModuleSync sync() {
        return sync;
    }

    /**
     * 把一段程序送去服务端跑。答复经 {@code done} 恰好交回一次:跑完的回执,或者(连整条消息的上限都装不下、服务端要的正文还是给不全)
     * 一张没跑成的失败回执。
     *
     * @param programId 这段程序的编号,调用方保证不重
     */
    public void run(UUID companion, String programId, String code, Consumer<RunResult> done) {
        send(companion, programId, new Waiting(companion, code, done, false));
    }

    /** 服务端答了。 */
    public void deliver(String programId, RunResult result) {
        Waiting w = waiting.get(programId);
        if (w == null) {
            return;   // 放弃了的
        }
        if (result instanceof RunResult.Missing missing) {
            sync.lost(missing.hashes());
            if (w.retried()) {
                waiting.remove(programId);
                w.done().accept(RunResult.refused("the server still lacks module texts after they were sent again: "
                        + String.join(", ", missing.hashes())));
                return;
            }
            send(w.companion(), programId, new Waiting(w.companion(), w.code(), w.done(), true));
            return;
        }
        waiting.remove(programId);
        if (result instanceof RunResult.Ended ended) {
            // 用到的每个模块记一次战绩:战绩记在客户端的账本里,和模块的真源在一处
            Modules mine = modules.apply(w.companion());
            for (Program.Used used : ended.outcome().used()) {
                mine.tally(used.module(), used.tally().ok(), used.tally().line(), used.tally().error(),
                        System.currentTimeMillis());
            }
        }
        w.done().accept(result);
    }

    /**
     * 这段程序所在的那一批作废了(切断),但程序在服务端照常停下、交出回执:晚到的结果交给 {@code late},而不是原来等的那一方。
     * 已经回来或根本没送出的,不做什么。
     */
    public void afterwards(String programId, Consumer<RunResult> late) {
        waiting.computeIfPresent(programId, (id, w) -> new Waiting(w.companion(), w.code(), late, w.retried()));
    }

    /** 不再等这段程序的答复(客户端放弃了):晚到的答复不理。 */
    public void forget(String programId) {
        waiting.remove(programId);
    }

    /**
     * 叫服务端上的这段程序停在调用之间:{@code why} 是给模型的原因(主人开口、客户端那边来了急件)。
     */
    public void interrupt(UUID companion, String programId, String why) {
        uplink.accept(new StopProgramPayload(companion, programId, false, false, why));
    }

    /**
     * 这一轮被切断,叫服务端上的这段程序当场停下。
     *
     * @param stopBody 身体活是不是一起叫停(叫停本身另发 {@code CancelTasksPayload}),回执照实说
     */
    public void cutOff(UUID companion, String programId, boolean stopBody) {
        uplink.accept(new StopProgramPayload(companion, programId, true, stopBody, ""));
    }

    /** 主人按停止:叫停她身体上在跑的活(程序另用 {@link #cutOff})。 */
    public void stopBody(UUID companion) {
        uplink.accept(new CancelTasksPayload(companion));
    }

    /** 连接断了:服务端按主人断线清掉了缓存,送过的记录作废;等着答复的不会再来了。 */
    public void disconnected() {
        sync.reset();
        waiting.clear();
    }

    private void send(UUID companion, String programId, Waiting w) {
        RunProgramPayload payload = new RunProgramPayload(companion, programId, w.code(),
                sync.pack(modules.apply(companion).sources()));
        int size = payload.size();
        if (!Wire.TO_SERVER.carries(size)) {
            sync.lost(payload.modules().bodies().keySet());
            waiting.remove(programId);
            w.done().accept(RunResult.refused(RunProgramPayload.tooBigWords(size)));
            return;
        }
        waiting.put(programId, w);
        uplink.accept(payload);
    }
}
