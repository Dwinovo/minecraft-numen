package com.dwinovo.numen.api.program;

import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.network.OwnedBody;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 座位送来的程序交给本进程里的 {@link ServerPrograms}:模块是座位按这一段程序的清单备好的({@link Modules});程序里的客户端函数经
 * {@link ClientTransport} 问主人的客户端。结局和 {@link ServerPrograms#run} 一样在服务端主线程上交回。
 *
 * <p>线程:都在服务端主线程上。
 */
public final class LocalPrograms implements ProgramRunner {

    private final MinecraftServer server;
    private final ClientTransport transport;

    /** @param transport 程序里的客户端函数怎么到主人的客户端 */
    public LocalPrograms(MinecraftServer server, ClientTransport transport) {
        this.server = server;
        this.transport = transport;
    }

    @Override
    public void run(UUID companion, String programId, String code, Modules modules, Consumer<Program.Outcome> done) {
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companion);
        if (entry == null) {
            done.accept(Program.Outcome.refused("companion not found (never summoned, or its data is gone)"));
            return;
        }
        NumenPlayer her = OwnedBody.of(server, entry.owner(), companion,
                message -> done.accept(Program.Outcome.refused(message)));
        if (her == null) {
            return;
        }
        ServerPrograms.run(her, companion, entry.owner(),
                new ServerPrograms.Request(programId, code, modules, true),
                transport, CallObserver.NONE, done);
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
