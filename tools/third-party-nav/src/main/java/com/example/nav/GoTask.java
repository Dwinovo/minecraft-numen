package com.example.nav;

import com.dwinovo.numen.api.agent.script.ErrorKind;
import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.InputDriver;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.Task;
import com.dwinovo.numen.api.task.TaskResult;
import com.dwinovo.numen.api.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 每刻朝目标转视角、按前进键(撞着东西就按跳),到了、或者连着一阵没有更近就交回结局。
 *
 * <p>它不改世界,所以没有要过权限层的动作;要挖、要放的寻路,动手前照 {@code Permission.gateFor(her)} 问一遍。
 */
final class GoTask implements Task {

    /** 水平上离目标中心这么近就算到了。 */
    private static final double ARRIVED = 0.7;
    /** 连着这么多刻没有更近就放弃。 */
    private static final int PATIENCE = 40;

    private final GoRecord record;
    private double best = Double.MAX_VALUE;
    private int sinceBetter;
    private String outcome = "";
    private boolean arrived;

    GoTask(GoRecord record) {
        this.record = record;
    }

    @Override
    public String name() {
        return record.getToolName();
    }

    @Override
    public TaskState tick(NumenPlayer her) {
        Vec3 goal = Vec3.atBottomCenterOf(record.target);
        double dx = goal.x - her.getX();
        double dz = goal.z - her.getZ();
        double away = Math.sqrt(dx * dx + dz * dz);
        if (away < ARRIVED) {
            arrived = true;
            outcome = "arrived at " + record.target.toShortString();
            return TaskState.SUCCESS;
        }
        if (away < best - 0.01) {
            best = away;
            sinceBetter = 0;
        } else if (++sinceBetter > PATIENCE) {
            outcome = "stopped getting closer to " + record.target.toShortString() + ", " + String.format("%.1f", away)
                    + " blocks short, standing at " + her.blockPosition().toShortString();
            return TaskState.FAILED;
        }
        InputDriver.lookAt(her, new Vec3(goal.x, her.getEyeY(), goal.z));
        Controls keys = her.controls();
        keys.press(Controls.Key.FORWARD);
        keys.set(Controls.Key.JUMP, her.horizontalCollision);
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer her, StopReason why) {
        her.controls().stop();
    }

    @Override
    public TaskResult result(TaskState terminal) {
        return switch (terminal) {
            case SUCCESS -> TaskResult.ok(outcome, new NavApi.Arrived(record.target));
            case TIMEOUT -> TaskResult.timeout("ran out of time walking to " + record.target.toShortString(), null);
            case CANCELLED -> TaskResult.cancelled("stopped before reaching " + record.target.toShortString());
            default -> TaskResult.fail(ErrorKind.FAILED, outcome, null);
        };
    }
}
