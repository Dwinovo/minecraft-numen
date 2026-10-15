package com.dwinovo.numen.api.task;

import com.dwinovo.numen.api.entity.NumenPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 空闲姿态层:几个姿态各管身体的一面(一个管视线,一个管跳),互不排斥,能做的都做——不像上面三层那样一刻只许一个拿身体。
 * 对 {@link TaskSelector} 它是一个任务:有一个姿态能做它就能做,拿到身体的每一刻把能做的姿态按登记序各 tick 一次,
 * 丢掉身体时让刚才在做的各自收尾。
 *
 * <p>登记序有意义:后面的在同一刻里后按键。说话看人({@code SpeakingLookChain})先停下脚步再转头,漂浮({@code FloatChain})排在
 * 它后面,才不会被那一下停步松掉跳。
 */
public final class Poses implements Task {

    private final List<Task> poses;
    /** 上一刻在做的姿态,丢掉身体时只让它们收尾。 */
    private final List<Task> acting = new ArrayList<>();

    public Poses(List<Task> poses) {
        this.poses = List.copyOf(poses);
    }

    @Override
    public boolean canRun(NumenPlayer companion) {
        for (Task pose : poses) {
            if (pose.canRun(companion)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        List<Task> now = new ArrayList<>();
        for (Task pose : poses) {
            if (pose.canRun(companion)) {
                pose.tick(companion);
                now.add(pose);
            }
        }
        // 这一刻不再做的姿态也要收尾:按下的键由大脑换驱动者时统一松开,自己的逻辑状态要自己清
        for (Task before : acting) {
            if (!now.contains(before)) {
                before.stop(companion, StopReason.PREEMPTED);
            }
        }
        acting.clear();
        acting.addAll(now);
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        for (Task pose : acting) {
            pose.stop(companion, why);
        }
        acting.clear();
    }

    @Override
    public String name() {
        return "poses";
    }
}
