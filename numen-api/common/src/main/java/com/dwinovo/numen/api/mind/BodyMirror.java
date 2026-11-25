package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.seat.BodyTask;
import com.dwinovo.numen.api.seat.SeatState;
import com.dwinovo.numen.api.agent.tool.ToolLine;

/**
 * 服务端告诉大脑的她此刻的样子({@link SeatState}),大脑这一头读的那份——服务端事实的缓存,只有服务端的消息写它(一轮开头的 {@code state/get}、程序结局、叫停的答复、{@code task_finished} 事件):给模型的 {@code <runtime_state>} 文字,与循环要的手上那件活的结构
 * (有没有活、有没有终点、给主人看的那一句)。样子是服务端读身体时写成的,这里只存最近收到的一份、不拼身体上的任何东西:一轮开头
 * {@code state/get} 取一份,这一轮里每份程序结局带来新的。
 *
 * <p>线程:大脑的线程(客户端主线程)。
 */
final class BodyMirror {

    private SeatState latest = new SeatState("", null, 0, 0);

    /** 收到一份新的样子。 */
    void take(SeatState state) {
        latest = state;
    }

    /** 最近的一份。 */
    SeatState latest() {
        return latest;
    }

    /** 给模型的 {@code <runtime_state>};没有可说的是空串。 */
    String xml() {
        return latest.text();
    }

    /** 手上的后台活收尾了(她的收件箱里收到了 {@code task_finished}):这一份说的活已经没有了。 */
    void taskFinished() {
        latest = new SeatState(latest.text(), null, latest.gameTime(), latest.dayTime());
    }

    /** 身体手上有没有后台活。 */
    boolean bodyTaskRunning() {
        return latest.task() != null;
    }

    /** 身体手上有一件会结束的活(常驻的跟随这种不算:它永远不报完成)。 */
    boolean bodyOnFiniteTask() {
        BodyTask task = latest.task();
        return task != null && !task.standing();
    }

    /**
     * 她此刻在干什么,{@code LoopStatus} 要的那一句:后台活优先(几十秒的长活,得看见她在挖矿而不是卡死了),
     * 没有才是手上这一个工具调用({@code tool}):名字之后接上 {@code summary}(工具自己说这次调用读作什么,没有就不接,读法见 {@link ToolLine});
     * 都没有返回 {@code null}。"在干什么"只在这一处合成。
     */
    LoopStatus.Doing doing(String tool, String summary) {
        BodyTask task = latest.task();
        if (task != null) {
            return new LoopStatus.Doing(task.activityKey(), task.activityArgs());
        }
        if (tool == null) {
            return null;
        }
        return new LoopStatus.Doing(ToolLine.key(tool), summary.isEmpty() ? java.util.List.of(tool) : java.util.List.of(tool, summary));
    }
}
