package com.dwinovo.numen.task.chain;

import com.dwinovo.numen.entity.CompanionSpeech;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.task.reflex.Reflex;
import net.minecraft.server.level.ServerPlayer;

/**
 * 说话看人——引擎自带的唯一姿态链:大脑在输出(思考/生成/跑工具/语音在播,
 * 客户端经 SpeakingStatePayload 报状态)且主人在近旁时,身体停下面向主人;
 * 其余时刻恒休眠。排在当前任务之下(第 4 层):任务在跑时自然让位
 * (挖着矿说话不回头,合理),任何反射更是随时抢得走。
 */
public final class SpeakingLookChain implements Task, Reflex {

    private static final int LOOK_RANGE = 16;

    @Override
    public boolean canRun(NumenPlayer companion) {
        if (!CompanionSpeech.isSpeaking(companion.getUUID())) return false;
        ServerPlayer owner = companion.resolveOwnerPlayer();
        return owner != null && owner.level() == companion.level()
                && companion.blockPosition().closerThan(owner.blockPosition(), LOOK_RANGE);
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner == null) return TaskState.RUNNING;
        InputDriver.halt(companion);
        InputDriver.lookAt(companion, owner.getEyePosition());
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        InputDriver.halt(companion);
    }

    @Override
    public String name() {
        return "speaking_look";
    }

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "说话时会停下面向主人";
    }
}
