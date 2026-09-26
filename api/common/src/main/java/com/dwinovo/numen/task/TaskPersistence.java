package com.dwinovo.numen.task;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.event.NumenEvents;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

/**
 * 她现在在做的事,活过服务器重启。
 *
 * <h2>为什么要有</h2>
 * 「去钓鱼」是一个<b>没有被收回的意图</b>。服务器重启对主人来说是不可见的实现细节,
 * 不该让他的指令蒸发——回来发现她站在湖边发呆、得再说一遍,那不是陪伴是打卡。
 *
 * <p>(主人单纯下线<b>不需要</b>这个:身体还在服务器里 tick,任务照样在跑,收尾走
 * {@code NumenEvents} 的离线出箱。这里只管重启。)
 *
 * <h2>重建配方 = 那次工具调用本身</h2>
 * 存两个字符串:{@code toolName} 与当时的 {@code args}。重建就是<b>把那次调用重放
 * 一遍</b>——工具作者一行都不用写,不需要给每个任务实现一套状态序列化。
 *
 * <p>另存一个名字:受理时这件活叫什么({@link TaskRecord#getToolName})。它不总是工具名——命令派的活叫"组 动作",
 * 重放用的工具却是 {@code command}——接不回来时告诉她的 task_finished 用的就是它,和她受理时看到的是同一个名字。
 *
 * <p>代价是<b>进度不保</b>:「挖 64 块」挖到 30 块重启,重放会重新挖 64 块。
 * 相比"回来发现啥也没干",多挖三十块是明显更小的损失;真在意精度的任务可以在
 * 自己的工具里把进度写进 args(那时它就是一次更精确的重放)。
 *
 * <h2>哪件是重启前留下的</h2>
 * 身体进世界那一刻落盘记录里的那件({@link #leftOver}),由调度器在那一刻接手、第一次 tick 时重放。
 * 不能等到第一次 tick 再读落盘记录:进世界与第一次 tick 之间派下的新活(调用唤醒休眠的她时就是同一刻)
 * 已经把记录改写成它自己,再读就会把刚派的活当成旧活重放一遍。那段间隙里派下新活,就是新活顶替了
 * 重启前那件({@link #superseded})。
 *
 * <p>重建失败不静默:任务开工后才发现的(鱼塘被填了、目标方块没了)由任务自己走 FAILED;重放本身没接住的
 * (工具没了、参数不成立、调用被拒)由 {@link #replay} 发 task_finished。
 *
 * <p>服务端专用。
 */
public final class TaskPersistence {

    /** 合成的调用 id 前缀——重放出来的任务不属于任何一次真实的 tool_call。 */
    private static final String REPLAY_CALL_ID = "restored";

    private TaskPersistence() {}

    /**
     * 记下她现在在做什么(换槽时调):{@code taskName} 是这件活的名字,{@code toolName} 与 {@code args} 是重放它的那次调用。
     * 全为 null = 记为空闲。
     */
    public static void remember(NumenPlayer companion, String taskName, String toolName, JsonObject args) {
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            return;
        }
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry e = reg.find(companion.getUUID());
        if (e == null) {
            return;
        }
        reg.put(companion.getUUID(), e.doing(taskName,
                toolName == null ? "" : toolName,
                args == null ? "" : args.toString()));
    }

    /** 她做完了 / 被换掉了 —— 清掉记录,免得重启后凭空捡回一件旧活。 */
    public static void forget(NumenPlayer companion) {
        remember(companion, null, null, null);
    }

    /** 重启前留下的一件活:它的名字,与重放它的那次调用。 */
    record LeftOver(String taskName, String toolName, String args) {}

    /** 身体进世界这一刻落盘记录里的那件活;没有就是 null。只在进世界那一刻读,见类注释。 */
    static LeftOver leftOver(NumenPlayer companion) {
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            return null;
        }
        CompanionRegistry.Entry e = CompanionRegistry.get(server).find(companion.getUUID());
        if (e == null || e.taskTool().isBlank()) {
            return null;
        }
        return new LeftOver(e.taskName(), e.taskTool(), e.taskArgs());
    }

    /**
     * 重启后把她手上的活接回来:重放那次工具调用。接不回来——工具没了、存下的参数读不了、重放被拒或参数
     * 已经不成立——都不阻断:身体照样起来,她空着手,并收到一条 task_finished 说清为什么。她的历史里还留着
     * "已受理,后台执行中"那句回执,不给个了结她会一直干等。
     */
    static void replay(NumenPlayer companion, LeftOver left) {
        String taskName = left.taskName();
        String toolName = left.toolName();
        NumenTool tool = ToolRegistry.get(toolName);
        if (tool == null) {
            // 工具在版本更新里没了(比如两个攻击工具并成了一个)。<b>不做兼容转接</b>——
            // 旧参数未必对得上新工具的语义,猜错了她会去打错的东西。
            abandon(companion, taskName, toolName + " 这个工具在这一版里已经不存在了。"
                    + "看看现在有哪些工具,需要的话重新派一次。");
            return;
        }
        JsonObject args;
        try {
            args = left.args().isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(left.args()).getAsJsonObject();
        } catch (RuntimeException bad) {
            abandon(companion, taskName, "重启前存下的参数读不了(" + bad.getMessage() + ")。需要的话重新派一次。");
            return;
        }
        Constants.LOG.info("[numen-task] {} 接回重启前的活:{} {}",
                companion.getUUID(), toolName, left.args());
        // 重放,和真实调用走同一个入口(NumenTool#serve)。受理的回执丢掉:它本来是给某一次 tool_call 的,那次
        // 调用早就随上一个会话结束了——真正会送到模型手里的是这件活干完时的 task_finished。被拒的回执(含参数
        // 已经不成立),就是这件活没接回来。
        tool.serve(REPLAY_CALL_ID + "-" + toolName, args, companion, reply -> {
            JsonObject result = JsonParser.parseString(reply).getAsJsonObject();
            if (!result.get("success").getAsBoolean()) {
                abandon(companion, taskName, result.get("message").getAsString() + "。需要的话重新派一次。");
            }
        });
    }

    /**
     * 这件活接不回来:记一笔,以它受理时的名字告诉她为什么,清掉记录。重放在第一次 tick 做,那之前没有新活派下来
     * (派了就不会重放,见 {@link #superseded}),所以此刻的记录就是这件。
     */
    private static void abandon(NumenPlayer companion, String taskName, String why) {
        Constants.LOG.warn("[numen-task] 重启前她在做的 {} 没能接回来: {}", taskName, why);
        NumenEvents.taskFinished(companion, REPLAY_CALL_ID + "-" + taskName, taskName, "failed",
                "这件活没能接回来:" + why);
        forget(companion);
    }

    /**
     * 重启前那件还没接回来,新活就派下来了:新的顶替它,和槽里有活时被换掉一样告诉她一声(她的历史里还留着
     * 那件的受理回执)。记录不动,新活受理时会记下它自己。
     */
    static void superseded(NumenPlayer companion, LeftOver left) {
        Constants.LOG.info("[numen-task] {} 重启前的 {} 还没接回来就被新派的活顶替", companion.getUUID(), left.taskName());
        NumenEvents.taskFinished(companion, REPLAY_CALL_ID + "-" + left.taskName(), left.taskName(), "stopped",
                "重启前的这件活还没接回来,新派的活顶替了它。");
    }
}
