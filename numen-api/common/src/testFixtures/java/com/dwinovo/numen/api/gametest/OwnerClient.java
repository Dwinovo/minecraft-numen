package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.brain.Presence;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.program.ClientTransport;
import com.dwinovo.numen.api.seat.SeatPipe;
import com.dwinovo.numen.api.seat.ServerSeats;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * GameTest 里主人的客户端,扮的:一个客户端经进程内的管道({@link SeatPipe})接在这台服务器真实的座位表上,服务端从座位表到身体的路——
 * 认主人、坐座位、反向请求、答复——都真走一遍,只是没有网线。同伴的座位在第一次有请求时坐上:这时她被登记进名册(创建者是她身体的主人,
 * 他的客户端报到齐了),座位才认他。客户端函数在发请求的线程上执行。
 *
 * <p>线程:服务端主线程(用例所在)。座位开出去要等服务端主线程处理,所以在这里让服务器边等边做排着的事({@code managedBlock})。
 */
public final class OwnerClient implements ClientTransport {

    private final MinecraftServer server;
    private final SeatPipe pipe;
    private final Set<UUID> seated = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public OwnerClient(MinecraftServer server, UUID owner) {
        this.server = server;
        Presence.reported(owner, Presence.PROTOCOL);   // 报到:协议对得上,再报联动提供什么
        Presence.ready(owner, Set.of());
        this.pipe = ServerSeats.pipe(owner, Runnable::run, Runnable::run);
    }

    /** 她坐在座位上之前先登记进名册、让座位认她的创建者。 */
    private void sit(UUID companion) {
        NumenPlayer her = NumenPlayer.findByUuid(server, companion);
        CompanionRegistry registry = CompanionRegistry.get(server);
        if (registry.find(companion) == null) {
            registry.put(companion, new CompanionRegistry.Entry(her.getGameProfile().getName(), her.getOwnerUuid(),
                    Level.OVERWORLD, her.blockPosition()));
        }
        CompletableFuture<?> opening = pipe.client().open(companion, 0);
        server.managedBlock(opening::isDone);
        opening.join();
    }

    @Override
    public void request(Request request, Consumer<String> done) {
        if (seated.add(request.companion())) {
            sit(request.companion());
        }
        ServerSeats.transport().request(request, done);
    }

    @Override
    public void cancel(String callId) {
        ServerSeats.transport().cancel(callId);
    }

    /** 从此这个"客户端"的答复都到不了服务端:程序里的客户端函数只能等到时限。 */
    public void silence() {
        pipe.silence();
    }
}
