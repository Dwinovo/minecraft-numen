package com.dwinovo.numen.api.program;

import com.dwinovo.numen.api.agent.script.Program;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.network.OwnedBody;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 服务端大脑把她的程序交给本进程里的 {@link ServerPrograms}:没有网络,没有包,模块直接读主人目录里的文件
 * ({@link Modules#of})。答复和 {@link ServerPrograms#run} 一样在服务端主线程上交回。
 *
 * <p>线程:都在服务端主线程上。
 */
public final class LocalPrograms implements ProgramHost {

    private final MinecraftServer server;
    /** 等答复的程序;切断后晚到的结果换一个收件人({@link #afterwards}),不等了的摘掉。 */
    private final Map<String, Consumer<RunResult>> waiting = new ConcurrentHashMap<>();

    public LocalPrograms(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void run(UUID companion, String programId, String code, Consumer<RunResult> done) {
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companion);
        if (entry == null) {
            done.accept(RunResult.refused("companion not found (never summoned, or its data is gone)"));
            return;
        }
        NumenPlayer her = OwnedBody.of(server, entry.owner(), companion,
                message -> done.accept(RunResult.refused(message)));
        if (her == null) {
            return;
        }
        waiting.put(programId, done);
        Modules mine = Modules.of(companion);
        ServerPrograms.run(her, companion, entry.owner(),
                new ServerPrograms.Request(programId, code, new ModuleSync().pack(mine.sources()), true),
                NetworkTransport.INSTANCE, CallObserver.NONE, result -> answered(programId, mine, result));
    }

    private void answered(String programId, Modules mine, RunResult result) {
        Consumer<RunResult> to = waiting.remove(programId);
        if (to == null) {
            return;   // 不等了的
        }
        if (result instanceof RunResult.Ended ended) {
            tally(mine, ended);
        }
        to.accept(result);
    }

    /** 用到的每个模块记一次战绩:战绩记在模块账本里,和模块的真源在一处。 */
    static void tally(Modules mine, RunResult.Ended ended) {
        for (Program.Used used : ended.outcome().used()) {
            mine.tally(used.module(), used.tally().ok(), used.tally().line(), used.tally().error(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public void afterwards(String programId, Consumer<RunResult> late) {
        waiting.computeIfPresent(programId, (id, was) -> late);
    }

    @Override
    public void forget(String programId) {
        waiting.remove(programId);
    }

    @Override
    public void interrupt(UUID companion, String programId, String why) {
        ServerPrograms.interrupt(companion, programId, why);
    }

    @Override
    public void cutOff(UUID companion, String programId, boolean stopBody) {
        ServerPrograms.cutOff(companion, programId, stopBody);
    }

    @Override
    public void stopBody(UUID companion) {
        // 跨维度找:她可能在下界干活。找不到就是身体已经不在了,没有活可停
        NumenPlayer her = NumenPlayer.findByUuid(server, companion);
        if (her != null) {
            CompanionTickDispatcher.cancelFor(her);
        }
    }
}
