package com.dwinovo.numen.task.chain;

import com.dwinovo.numen.entity.CompanionSpeech;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskChain;
import com.dwinovo.numen.task.reflex.Reflex;
import com.dwinovo.numen.task.reflex.ReflexRegistry;
import net.minecraft.server.level.ServerPlayer;

/**
 * 说话看人——引擎自带的唯一姿态链:大脑在输出(思考/生成/跑工具/语音在播,
 * 客户端经 SpeakingStatePayload 报状态)且主人在近旁时,身体停下面向主人;
 * 其余时刻恒休眠。出价在 LLM 基准价之下:任务在跑时自然让位(挖着矿说话
 * 不回头,合理),任何本能更是随时抢得走。
 */
public final class SpeakingLookChain implements TaskChain, Reflex {

    private static final float PRIORITY = TaskChain.LLM_BASE_PRIORITY - 1.0f;
    private static final int LOOK_RANGE = 16;

    @Override
    public float getPriority(NumenPlayer companion) {
        if (!ReflexRegistry.enabled(id())) return Float.NEGATIVE_INFINITY;
        if (!CompanionSpeech.isSpeaking(companion.getUUID())) return Float.NEGATIVE_INFINITY;
        ServerPlayer owner = companion.resolveOwnerPlayer();
        boolean near = owner != null && owner.level() == companion.level()
                && companion.blockPosition().closerThan(owner.blockPosition(), LOOK_RANGE);
        return near ? PRIORITY : Float.NEGATIVE_INFINITY;
    }

    @Override
    public void tick(NumenPlayer companion) {
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner == null) return;
        InputDriver.halt(companion);
        InputDriver.lookAt(companion, owner.getEyePosition());
    }

    @Override
    public void onInterrupt(NumenPlayer companion) {
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
