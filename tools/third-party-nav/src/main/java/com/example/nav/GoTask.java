package com.example.nav;

import com.dwinovo.numen.api.agent.script.ErrorKind;
import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.Verdict;
import com.dwinovo.numen.api.task.Task;
import com.dwinovo.numen.api.task.TaskResult;
import com.dwinovo.numen.api.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * 每刻朝目标转视角、按前进键(撞着东西就按跳),到了、或者连着一阵没有更近就交回结局。走不动时,挡在正前方的整块方块就挖开:
 * 转过去看着它、按住左键——和真人一样,用的是她的视角({@code look()})与鼠标({@code mouse()}),不另写一套挖法。
 *
 * <p>每一下挖之前过不过权限层是鼠标自己的事(同伴的鼠标动手前先问):不许就带着理由失败;要问主人的格这个样例也当作没让挖,
 * 要发起征询的寻路在这里接 {@code Mouse.Refusal.Asks}。
 */
final class GoTask implements Task {

    /** 水平上离目标中心这么近就算到了。 */
    private static final double ARRIVED = 0.7;
    /** 连着这么多刻没有更近就放弃(挖开挡路的方块不算)。 */
    private static final int PATIENCE = 40;
    /** 连着这么多刻没有更近,正前方又有整块方块,就挖它。 */
    private static final int STUCK = 10;

    private final GoRecord record;
    private double best = Double.MAX_VALUE;
    private int sinceBetter;
    private String outcome = "";

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
        BlockPos blocking = sinceBetter > STUCK ? blockingAhead(her, Direction.getNearest(dx, 0, dz)) : null;
        if (blocking != null) {
            return dig(her, blocking);
        }
        her.mouse().release();
        her.look().at(new Vec3(goal.x, her.getEyeY(), goal.z));
        Controls keys = her.controls();
        keys.press(Controls.Key.FORWARD);
        keys.set(Controls.Key.JUMP, her.horizontalCollision);
        return TaskState.RUNNING;
    }

    /** 正前方挡路的整块方块:脚那一格先,再是头那一格;没有为 null。 */
    private static BlockPos blockingAhead(NumenPlayer her, Direction toward) {
        BlockPos feet = her.blockPosition().relative(toward);
        for (BlockPos cell : new BlockPos[] {feet, feet.above()}) {
            if (!her.level().getBlockState(cell).getCollisionShape(her.level(), cell).isEmpty()) {
                return cell;
            }
        }
        return null;
    }

    /** 站住,看着它看得见的那一点,按住左键;碎了就接着走。 */
    private TaskState dig(NumenPlayer her, BlockPos cell) {
        her.controls().stop();
        Vec3 point = her.look().point(cell);
        if (point == null) {
            return TaskState.RUNNING;   // 看不见它(被别的挡着):等,耐心一到就放弃
        }
        her.look().at(point);
        if (her.mouse().on(cell) == null) {
            return TaskState.RUNNING;
        }
        if (her.mouse().dig() instanceof Mouse.Strike.Refused refused) {
            Verdict verdict = refused.reason().verdict();
            outcome = "not allowed to break " + cell.toShortString() + ": "
                    + (verdict != null ? verdict.reason() : "the server would not let it happen");
            return TaskState.FAILED;
        }
        sinceBetter = STUCK;   // 在挖就是在推进:耐心不往下数,碎了接着走
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer her, StopReason why) {
        // 被顶掉、被换掉,身体上的键与挖掘进度都由大脑统一松开,这里不必松
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
