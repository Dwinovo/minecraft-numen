package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.sdk.OnHer;
import com.dwinovo.numen.sdk.ServerCall;
import com.dwinovo.numen.task.TaskRecord;

/** {@code ysm.model.switch} 派下来的一次换装:换成哪个模型、哪张贴图,以及以服务器权威、只对她执行命令的那条路。 */
final class SwitchRecord extends TaskRecord {

    /** 期限(游戏刻):命令在拿到身体的第一刻就执行完;宽出来的是身体被本能占着、轮不到它的那几刻。 */
    private static final int TIMEOUT_TICKS = 5 * 20;

    final OnHer her;
    final Ysm.Look look;

    SwitchRecord(ServerCall source, OnHer her, Ysm.Look look) {
        super(source, source.her().level().getGameTime() + TIMEOUT_TICKS);
        this.her = her;
        this.look = look;
    }

    @Override
    public String describe() {
        return getToolName() + " " + look.model() + " " + look.texture();
    }
}
