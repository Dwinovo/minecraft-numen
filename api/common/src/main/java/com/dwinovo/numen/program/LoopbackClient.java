package com.dwinovo.numen.program;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.Fragments;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.network.payload.ProgramResultPayload;
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
 * 一个在本进程里扮主人客户端的"客户端":没有真客户端的地方(GameTest、单测、评测)用它把程序送进服务端的入口。送程序、停程序、
 * 收回执、答反向请求用的都是产品里的同一批部件({@link ProgramUplink}、{@link ClientEndpoint}、{@link ModuleSync}),上行与下行的包
 * 经过线上的样子({@link Fragments#crossed}:超过单包上限的切成片再拼回、各编码再解码一遍),只是不出进程;服务端那头直接是 {@link ServerPrograms}(省掉"认出是哪具身体、是不是主人"那一步网络层的校验)。
 */
public final class LoopbackClient {

    private final Function<UUID, NumenPlayer> bodies;
    private final ProgramUplink uplink;
    private final LoopbackTransport transport;
    /** 每段在跑的程序的观察者,按程序编号。 */
    private final Map<String, CallObserver> observers = new ConcurrentHashMap<>();

    /**
     * @param bodies  认出同伴的身体;没有世界的单测是 {@code uuid -> null}
     * @param modules 她的模块在这个"客户端"的哪里:GameTest 与评测是这一次专用的目录({@code Modules::of}),只要出厂那一套的单测是
     *                {@code uuid -> Modules.factory()}
     */
    public LoopbackClient(Function<UUID, NumenPlayer> bodies, Function<UUID, Modules> modules) {
        this.bodies = bodies;
        ModuleSync sync = new ModuleSync();
        this.uplink = new ProgramUplink(sync, this::toServer, modules);
        this.transport = new LoopbackTransport(sync, modules);
    }

    /**
     * 送一段程序去跑。回执经 {@code done} 恰好交回一次(在服务端主线程上,也就是推进车道的那个线程)。
     *
     * @param observer 看着它的每次调用
     */
    public void run(UUID companion, String programId, String code, CallObserver observer, Consumer<RunResult> done) {
        observers.put(programId, observer);
        uplink.run(companion, programId, code, result -> {
            observers.remove(programId);
            done.accept(result);
        });
    }

    /** 这个"客户端"的上行部件:用例拿它造客户端的工具口。 */
    public ProgramUplink uplink() {
        return uplink;
    }

    /** 让这个"客户端"从此不再答复反向请求(扮一个卡住的客户端):程序里的客户端函数要等到时限才以失败结束。 */
    public void silence() {
        transport.silence();
    }

    /** 主人开口、客户端那边来了急件:让这段程序停在调用之间。 */
    public void interrupt(UUID companion, String programId, String why) {
        uplink.interrupt(companion, programId, why);
    }

    /** 这一轮被切断(主人按停止):这段程序当场停下。 */
    public void cutOff(UUID companion, String programId, boolean stopBody) {
        uplink.cutOff(companion, programId, stopBody);
    }

    private void toServer(CustomPacketPayload payload) {
        switch (payload) {
            case RunProgramPayload run -> {
                RunProgramPayload sent = Fragments.crossed(Wire.TO_SERVER, RunProgramPayload.STREAM_CODEC, run);
                ServerPrograms.run(bodies.apply(sent.entityUuid()), sent.entityUuid(), sent.entityUuid(),
                        new ServerPrograms.Request(sent.programId(), sent.code(), sent.modules(), true), transport,
                        observers.getOrDefault(sent.programId(), CallObserver.NONE), result -> {
                            ProgramResultPayload back = Fragments.crossed(Wire.TO_CLIENT, ProgramResultPayload.STREAM_CODEC,
                                    new ProgramResultPayload(sent.entityUuid(), sent.programId(), result.toJson()));
                            uplink.deliver(back.programId(), RunResult.fromJson(back.resultJson()));
                        });
            }
            case StopProgramPayload stop -> {
                StopProgramPayload sent = Fragments.crossed(Wire.TO_SERVER, StopProgramPayload.STREAM_CODEC, stop);
                if (sent.cutOff()) {
                    ServerPrograms.cutOff(sent.entityUuid(), sent.programId(), sent.stopBody());
                } else {
                    ServerPrograms.interrupt(sent.entityUuid(), sent.programId(), sent.why());
                }
            }
            case com.dwinovo.numen.network.payload.CancelTasksPayload cancel -> {
                NumenPlayer body = bodies.apply(cancel.entityUuid());
                if (body != null) {
                    com.dwinovo.numen.task.CompanionTickDispatcher.cancelFor(body);
                }
            }
            default -> throw new IllegalArgumentException("the loopback client does not send " + payload.type().id());
        }
    }
}
