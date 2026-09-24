package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * 换一身模型:执行 YSM 的 {@code ysm model set},再回读她身上穿的,模型真变了才算换好。
 *
 * <h2>为什么在任务里做</h2>
 * 成败要以 YSM 真正设上为准,也就是回读之前那条命令必须已经执行完。原版的指令在另一条指令的执行当中被调起时会
 * 排到那条指令之后(见 {@link Ysm#setModel} 背后的 {@code run}),命令处理函数又可能正是从那种地方调进来的;
 * 任务在服务器刻里跑,不在任何一条指令的执行当中,命令当场执行完,紧接着的回读读到的就是结果。
 *
 * <h2>没换成时说什么</h2>
 * YSM 对这条命令说的话原样转给她(要授权、没有这个模型……);它不说话的时候如实说它没说原因,不替它猜。
 */
final class SwitchTask implements Task {

    private final Ysm ysm;
    private final SwitchRecord r;
    /** 走到终态时说给模型听的那句话。 */
    private String outcome = "";

    SwitchTask(Ysm ysm, SwitchRecord record) {
        this.ysm = ysm;
        this.r = record;
    }

    @Override
    public TaskState tick(NumenPlayer her) {
        MinecraftServer server = her.level().getServer();
        String model = r.look.model();
        List<String> said = ysm.setModel(server, her.getName().getString(), r.look);
        Ysm.Look now = ysm.readLook(her);
        if (now != null && model.equals(now.model())) {
            outcome = "换好了:" + model;
            return TaskState.SUCCESS;
        }
        outcome = "没换成 '" + model + "'。" + (said.isEmpty() ? "YSM 没有说原因" : "YSM 说:" + String.join(" ", said))
                + ";现在穿的还是 " + (now == null ? "(读不到)" : now.model());
        return TaskState.FAILED;
    }

    /** 不动导航,没有要放的东西。 */
    @Override
    public void stop(NumenPlayer her, StopReason why) {}

    @Override
    public TaskResult result(TaskState terminal) {
        return switch (terminal) {
            case SUCCESS -> TaskResult.ok(outcome);
            case TIMEOUT -> TaskResult.timeout("没轮到换成 '" + r.look.model() + "' 就到期了,模型没动");
            case CANCELLED -> TaskResult.cancelled("换成 '" + r.look.model() + "' 之前被叫停了,模型没动");
            default -> TaskResult.fail(outcome);
        };
    }

    @Override
    public String name() {
        return YsmCommands.GROUP + " " + YsmCommands.SWITCH;
    }
}
