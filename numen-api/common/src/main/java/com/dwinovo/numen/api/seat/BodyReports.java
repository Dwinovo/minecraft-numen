package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.request.BodySnapshot;
import com.dwinovo.numen.api.agent.request.Nearby;
import com.dwinovo.numen.api.agent.request.RuntimeState;
import com.dwinovo.numen.api.agent.request.TaskReport;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.network.payload.RequestStatePayload;
import com.dwinovo.numen.api.task.Activity;
import com.dwinovo.numen.api.text.ServerLang;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 她此刻的样子({@link SeatState})在服务端<b>唯一</b>写成的地方:读身体与世界,渲染 {@code <runtime_state>},并把任务槽报来的
 * 手上那件活({@link TaskReport})同时写成给模型的文字与循环要的结构,两份出自同一份快照。每只同伴一份渲染器,背包块在快照没变时复用。
 *
 * <p>线程:服务端主线程。
 */
final class BodyReports {

    /** 一只同伴的渲染器和任务槽最近报来的那一份。 */
    private static final class Report {
        final RuntimeState runtime;
        TaskReport task = TaskReport.none();

        Report(RuntimeState runtime) {
            this.runtime = runtime;
        }
    }

    private final MinecraftServer server;
    private final Map<UUID, Report> reports = new ConcurrentHashMap<>();

    BodyReports(MinecraftServer server) {
        this.server = server;
    }

    /** 任务槽说她在做什么(照抄,不推断)。 */
    void task(UUID companion, TaskReport report) {
        Report mine = of(companion);
        mine.task = report;
        mine.runtime.onCurrentTask(report);
    }

    /** 她被遣散了。 */
    void forget(UUID companion) {
        reports.remove(companion);
    }

    /** 此刻的样子。 */
    SeatState state(UUID companion) {
        Report mine = of(companion);
        TaskReport task = mine.task;
        BodyTask doing = task.isIdle() ? null : new BodyTask(task.taskId(), task.tool(), task.standing(),
                task.activity().key(), task.activity().args());
        return new SeatState(mine.runtime.xml(), doing, server.overworld().getGameTime(),
                server.overworld().getDayTime());
    }

    private Report of(UUID companion) {
        return reports.computeIfAbsent(companion, id -> new Report(new RuntimeState(id,
                () -> NumenPlayer.findByUuid(server, id),
                () -> {
                    NumenPlayer body = NumenPlayer.findByUuid(server, id);
                    return body == null ? null
                            : BodySnapshot.of(RequestStatePayload.snapshot(body), System.currentTimeMillis())
                                    .withNearby(Nearby.of(body));
                },
                () -> {
                    NumenPlayer body = NumenPlayer.findByUuid(server, id);
                    ServerPlayer keeper = body == null ? null : body.keeperAtHand();
                    return keeper == null ? null : ServerLang.languageName(ServerLang.localeOf(keeper));
                },
                () -> server.overworld().getDayTime())));
    }
}
