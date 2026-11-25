package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.agent.trace.TraceRecorder;
import com.dwinovo.numen.api.agent.trace.TraceRecorders;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.google.gson.JsonObject;

import java.nio.file.Path;

/**
 * 她的身体在服务端写的旁路轨迹:寻路为什么放弃一类的、身体这一头发生的事。写在世界存档里她的家下({@code trace/}),由服务端自己写;
 * 她的大脑在主人的客户端上另写一本,只记它自己的每一轮。两本互不相识,也不经任何静态表互相够得着。
 *
 * <p>这个进程不记轨迹({@link TraceRecorders#recording})时,什么也不写。
 *
 * <p>线程:任何线程。
 */
public final class BodyTrace {

    /** 不记轨迹时是 {@code null}。 */
    private final TraceRecorder recorder;

    private BodyTrace(NumenPlayer her) {
        if (TraceRecorders.recording()) {
            Home home = Homes.of(her.server).companion(her.getUUID());
            this.recorder = new TraceRecorder(home.dir(), home.io(), her.getUUID());
        } else {
            this.recorder = null;
        }
    }

    /** 这具身体的轨迹(首次取时建)。 */
    public static BodyTrace of(NumenPlayer her) {
        return her.state(BodyTrace.class, () -> new BodyTrace(her));
    }

    /** 记一条;不记轨迹时什么也不做。 */
    public void event(String type, JsonObject body) {
        if (recorder != null) {
            recorder.event(type, body);
        }
    }

    /** 正在写的那一段;不记轨迹时是 {@code null}。 */
    public Path file() {
        return recorder == null ? null : recorder.file();
    }
}
