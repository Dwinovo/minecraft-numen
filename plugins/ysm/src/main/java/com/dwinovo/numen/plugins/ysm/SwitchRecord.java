package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;

/** {@code numen ysm switch} 派下来的一次换装:换成哪个模型、哪张贴图。 */
final class SwitchRecord extends TaskRecord {

    /** 期限(游戏刻):命令在拿到身体的第一刻就执行完;宽出来的是身体被本能占着、轮不到它的那几刻。 */
    private static final int TIMEOUT_TICKS = 5 * 20;

    final Ysm.Look look;

    SwitchRecord(ServerSource source, Ysm.Look look) {
        super(source, source.companion().level().getGameTime() + TIMEOUT_TICKS);
        this.look = look;
    }

    @Override
    public String describe() {
        return getToolName() + " " + look.model() + " " + look.texture();
    }
}
