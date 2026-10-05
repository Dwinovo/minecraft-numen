package com.dwinovo.numen.task;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.event.NumenEvents;
import com.dwinovo.numen.program.CallObserver;
import com.dwinovo.numen.program.ServerPrograms;
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
 * <h2>重建配方 = 那次调用写成的一行 Lua</h2>
 * 存派活的那次调用写成的一行 Lua(派发按函数的参数表写,活换过参数的是换过的那一份,见 {@code Job#replayedAs})。重建就是
 * <b>把那一行在服务端再跑一遍</b>({@link ServerPrograms}),和她自己写的程序同一个入口——函数作者一行都不用写,不需要给每个任务实现
 * 一套状态序列化。另存一个名字:受理时这件活叫什么(派它的函数的全名);接不回来时告诉她的 task_finished 用的就是它。
 *
 * <p>代价是<b>进度不保</b>:「挖 64 块」挖到 30 块重启,再跑会重新挖 64 块。相比"回来发现啥也没干",多挖三十块是明显更小的损失。
 *
 * <h2>哪件是重启前留下的</h2>
 * 身体进世界那一刻落盘记录里的那件({@link #leftOver}),由调度器在那一刻接手、第一次 tick 时再跑。
 * 不能等到第一次 tick 再读落盘记录:进世界与第一次 tick 之间派下的新活(调用唤醒休眠的她时就是同一刻)
 * 已经把记录改写成它自己,再读就会把刚派的活当成旧活再跑一遍。那段间隙里派下新活,就是新活顶替了
 * 重启前那件({@link #superseded})。
 *
 * <p>接不回来不静默:任务开工后才发现的由任务自己走 FAILED;再跑本身没接住的(函数没了、那一行读不通、调用被拒、受理之前的准备
 * 没过——鱼塘被填了、目标方块没了、路不通了)由 {@link #replay} 发 task_finished。旧版本按一行命令记下的活这一版不再跑:读到就
 * 丢掉,日志与 task_finished 如实说。
 *
 * <p>服务端专用。
 */
public final class TaskPersistence {

    /** 合成的调用 id 前缀——再跑出来的任务不属于任何一次真实的 tool_call。 */
    private static final String REPLAY_CALL_ID = "restored";

    private TaskPersistence() {}

    /**
     * 记下她现在在做什么(换槽时调):{@code taskName} 是这件活的名字,{@code lua} 是重启后再跑的那一行。全为 null = 记为空闲;
     * 只有名字没有那一行 = 在做、但接不回来。
     */
    public static void remember(NumenPlayer companion, String taskName, String lua) {
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            return;
        }
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry e = reg.find(companion.getUUID());
        if (e == null) {
            return;
        }
        reg.put(companion.getUUID(), e.doing(taskName, lua));
    }

    /** 她做完了 / 被换掉了 —— 清掉记录,免得重启后凭空捡回一件旧活。 */
    public static void forget(NumenPlayer companion) {
        remember(companion, null, null);
    }

    /**
     * 重启前留下的一件活:它的名字,与再跑它的那一行 Lua。
     *
     * @param old 旧版本记下的那一行命令(这一版不再跑);是新的写法时为空串
     */
    record LeftOver(String taskName, String lua, String old) {}

    /** 身体进世界这一刻落盘记录里的那件活;没有就是 null。只在进世界那一刻读,见类注释。 */
    static LeftOver leftOver(NumenPlayer companion) {
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            return null;
        }
        CompanionRegistry.Entry e = CompanionRegistry.get(server).find(companion.getUUID());
        if (e == null || (e.taskLua().isBlank() && e.taskOld().isBlank())) {
            return null;
        }
        return new LeftOver(e.taskName().isBlank() ? "the task" : e.taskName(), e.taskLua(), e.taskOld());
    }

    /**
     * 重启后把她手上的活接回来:把记下的那一行 Lua 在服务端再跑一遍。接不回来——函数在这一版里没了、那一行读不通、调用被拒、参数
     * 已经不成立或受理之前的准备没过——都不阻断:身体照样起来,她空着手,并收到一条 task_finished 说清为什么。她的历史里还留着那件活,
     * 不给个了结她会一直干等。<b>不做兼容转接</b>:旧版本记下的一行命令不再跑,猜它的意思她可能去打错的东西。
     */
    static void replay(NumenPlayer companion, LeftOver left) {
        String taskName = left.taskName();
        if (left.lua().isBlank()) {
            Constants.LOG.warn("[numen-task] {} 重启前的活是旧版本按一行命令记下的,这一版不再跑它,丢掉:{}",
                    companion.getUUID(), left.old());
            abandon(companion, taskName, "it was saved by an older version of Numen as a command line ("
                    + left.old() + "), which this version no longer runs. Start it again if it is still wanted.");
            return;
        }
        Constants.LOG.info("[numen-task] {} 接回重启前的活:{}", companion.getUUID(), left.lua());
        // 再跑,和她写的程序走同一个入口。受理了,它干完时的 task_finished 照常送到她手里;没受理(读不通、调用被拒、准备没过)就是
        // 这件活没接回来,程序的回执说为什么
        boolean[] accepted = {false};
        ServerPrograms.launch(companion, REPLAY_CALL_ID + "-" + taskName, left.lua(), new CallObserver() {
            @Override
            public void replied(String callId, String reply) {
                ApiReply.Parsed parsed = ApiReply.parse(reply);
                accepted[0] |= parsed.ok() && parsed.job() != null;
            }
        }, receipt -> {
            JsonObject result = JsonParser.parseString(receipt).getAsJsonObject();
            if (!accepted[0]) {
                abandon(companion, taskName, result.get("message").getAsString()
                        + " Start it again if it is still wanted.");
            }
        });
    }

    /**
     * 这件活接不回来:记一笔,以它受理时的名字告诉她为什么,清掉记录。
     */
    private static void abandon(NumenPlayer companion, String taskName, String why) {
        Constants.LOG.warn("[numen-task] 重启前她在做的 {} 没能接回来: {}", taskName, why);
        NumenEvents.taskFinished(companion, REPLAY_CALL_ID + "-" + taskName, taskName, "failed",
                TaskResult.fail("this task could not be resumed after the restart: " + why), null);
        forget(companion);
    }

    /**
     * 重启前那件还没接回来,新活就派下来了:新的顶替它,和槽里有活时被换掉一样告诉她一声(她的历史里还留着
     * 那件)。记录不动,新活受理时会记下它自己。
     */
    static void superseded(NumenPlayer companion, LeftOver left) {
        Constants.LOG.info("[numen-task] {} 重启前的 {} 还没接回来就被新派的活顶替", companion.getUUID(), left.taskName());
        NumenEvents.taskFinished(companion, REPLAY_CALL_ID + "-" + left.taskName(), left.taskName(), "stopped",
                TaskResult.cancelled("the task from before the restart was not resumed: a newer one replaced it."),
                null);
    }
}
