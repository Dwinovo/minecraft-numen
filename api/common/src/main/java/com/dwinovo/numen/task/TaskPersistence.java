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
 * <p>代价是<b>进度不保</b>:「挖 64 块」挖到 30 块重启,重放会重新挖 64 块。
 * 相比"回来发现啥也没干",多挖三十块是明显更小的损失;真在意精度的任务可以在
 * 自己的工具里把进度写进 args(那时它就是一次更精确的重放)。
 *
 * <p>重建失败(鱼塘被填了、目标方块没了)不静默:任务自己走 FAILED,收尾事件照发。
 *
 * <p>服务端专用。
 */
public final class TaskPersistence {

    /** 合成的调用 id 前缀——重放出来的任务不属于任何一次真实的 tool_call。 */
    private static final String REPLAY_CALL_ID = "restored";

    private TaskPersistence() {}

    /** 记下她现在在做什么(换槽时调)。{@code toolName} 为 null = 记为空闲。 */
    public static void remember(NumenPlayer companion, String toolName, JsonObject args) {
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            return;
        }
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry e = reg.find(companion.getUUID());
        if (e == null) {
            return;
        }
        reg.put(companion.getUUID(), e.doing(
                toolName == null ? "" : toolName,
                args == null ? "" : args.toString()));
    }

    /** 她做完了 / 被换掉了 —— 清掉记录,免得重启后凭空捡回一件旧活。 */
    public static void forget(NumenPlayer companion) {
        remember(companion, null, null);
    }

    /**
     * 重启后把她手上的活接回来。重放那次工具调用;工具没了、参数坏了、或者调用被
     * 拒绝,都只记日志不阻断——身体照样起来,她只是空着手。
     */
    public static void restore(NumenPlayer companion) {
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            return;
        }
        CompanionRegistry.Entry e = CompanionRegistry.get(server).find(companion.getUUID());
        if (e == null || e.taskTool().isBlank()) {
            return;
        }
        NumenTool tool = ToolRegistry.get(e.taskTool());
        if (tool == null) {
            // 工具在版本更新里没了(比如两个攻击工具并成了一个)。<b>不做兼容转接</b>——
            // 旧参数未必对得上新工具的语义,猜错了她会去打错的东西。丢掉,然后告诉她:
            // 她的历史里还留着"已受理,后台执行中"那句回执,不给个了结她会一直干等。
            Constants.LOG.warn("[numen-task] 重启前她在做的 {} 现在没有这个工具了,放弃恢复",
                    e.taskTool());
            NumenEvents.taskFinished(companion, "restored-" + e.taskTool(), e.taskTool(), "failed",
                    "这件活没能接回来:" + e.taskTool() + " 这个工具在这一版里已经不存在了。"
                            + "看看现在有哪些工具,需要的话重新派一次。");
            forget(companion);
            return;
        }
        JsonObject args;
        try {
            args = e.taskArgs().isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(e.taskArgs()).getAsJsonObject();
        } catch (RuntimeException bad) {
            Constants.LOG.warn("[numen-task] {} 存下的参数读不了,放弃恢复: {}",
                    e.taskTool(), bad.toString());
            forget(companion);
            return;
        }
        Constants.LOG.info("[numen-task] {} 接回重启前的活:{} {}",
                companion.getUUID(), e.taskTool(), e.taskArgs());
        // 重放。回执直接丢:它本来是给某一次 tool_call 的,而那次调用早就随上一个
        // 会话结束了——真正会送到模型手里的是这件活干完时的 task_finished。
        tool.onServerCall(REPLAY_CALL_ID + "-" + e.taskTool(), args, companion, reply -> { });
    }
}
