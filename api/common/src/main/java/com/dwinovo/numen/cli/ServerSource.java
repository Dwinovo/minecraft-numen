package com.dwinovo.numen.cli;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Gate;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.function.Consumer;

/**
 * 服务端的一次调用:活体、调用 id、回信口,以及读到的动作与参数。服务端动作的处理函数拿到的就是它——从脚本进来(经
 * {@link NumenCli#serve(String, JsonObject, NumenPlayer, String, Consumer)})与从一行命令进来({@link CommandRunner})是同一个形状。
 *
 * <p>长活交给 {@code TaskDispatch.setTask(source, record)} 时,重启后的重放记的是这次调用读到的参数写回的那一行命令
 * ({@link #replayLine()},{@link CommandArgs#write} 按这个动作的参数表写),走一行命令的前端再来一遍。参数里有只在这一次开服里
 * 有效的写法(实体的运行期编号)时,处理函数把它换成跨重启不变的写法,重放记的是换过的那一份({@link #replayedWith})。
 *
 * <p>给模型看的任务名见 {@link #taskName()}:它是脚本里的函数名,她刚才调的就是它。
 */
public final class ServerSource implements CommandSource {

    private final NumenPlayer companion;
    private final String toolCallId;
    private final Consumer<String> reply;
    /** 读到的动作;交给处理函数之前由 {@link #running} 绑上。 */
    private final Action action;
    /** 主人为这次调用点了头时,回执末尾交代的那一句;没问过主人为 null。 */
    private final String allowance;
    /** 重启后重放的那一份参数;没绑上动作之前是 null。 */
    private final CommandArgs replayArgs;

    ServerSource(NumenPlayer companion, String toolCallId, Consumer<String> reply) {
        this(companion, toolCallId, reply, null, null, null);
    }

    private ServerSource(NumenPlayer companion, String toolCallId, Consumer<String> reply, Action action,
                         String allowance, CommandArgs replayArgs) {
        this.companion = companion;
        this.toolCallId = toolCallId;
        this.reply = reply;
        this.action = action;
        this.allowance = allowance;
        this.replayArgs = replayArgs;
    }

    /** 同一次调用,绑上读到的动作与参数:重放的那一行由它们写出。 */
    ServerSource running(Action action, CommandArgs args) {
        return new ServerSource(companion, toolCallId, reply, action, allowance, args);
    }

    /** 同一次调用,主人点了头:回执末尾交代 {@code allowance} 这一句。 */
    ServerSource allowed(String allowance) {
        return new ServerSource(companion, toolCallId, reply, action, allowance, replayArgs);
    }

    /**
     * 同一次调用,重启后重放的是 {@code stable} 写回的那一行命令({@link CommandArgs#write} 按这个动作的参数表写):处理函数把只在
     * 这一次开服里有效的值换成跨重启不变的写法(实体的运行期编号换成 {@link EntityRef#of 它的 UUID}),交给
     * {@code TaskDispatch.setTask}。回执、任务名、调用 id 都还是这次调用的。
     */
    public ServerSource replayedWith(CommandArgs stable) {
        return new ServerSource(companion, toolCallId, reply, action, allowance, stable);
    }

    /** 读到的动作的路径:{@code move go}。 */
    public String actionPath() {
        return action.path();
    }

    /** 重启后重放的那一行命令。 */
    public String replayLine() {
        return replayArgs.write(action.path(), action.params());
    }

    /** 这具身体。 */
    public NumenPlayer companion() {
        return companion;
    }

    /**
     * 以服务器的权威、只对她执行原版与模组指令的那条路。只有声明了 {@link Authority#SERVER_ON_HER} 的动作拿得到;
     * 没声明的动作来拿就抛出——权威只在动作的声明里给,处理函数不另开后门。
     */
    public OnHer onHer() {
        if (action.authority() != Authority.SERVER_ON_HER) {
            throw new IllegalStateException(action.path() + " runs with her own authority; declare "
                    + "authority(Authority.SERVER_ON_HER) to borrow the server's");
        }
        return new OnHer(companion);
    }

    /**
     * 这次调用派下的活叫什么——任务记录、{@code task_finished}、{@code <current_task>} 里写的名字:脚本里的函数名
     * (如 {@code kaleidoscope.pot.cook})。模型看到的就是它刚才调的那个函数。
     */
    public String taskName() {
        return action.function();
    }

    /** 这次调用的 id,要跟着结果回去。 */
    public String toolCallId() {
        return toolCallId;
    }

    /**
     * 这次调用要做的一件事先过权限层,在主线程对活世界裁决:放行就接着走({@code go});不许就带着理由回执、不走;要问就挂起
     * 这次调用({@link PendingCommands}),主人答复后再走或如实回执。等的只是这一次调用,不占任务槽。原版指令与改一块区域都经这里,
     * 是同一个口子。
     *
     * @param action 要做的事,如 {@code command(setblock)}、{@code break(placed)}
     * @param what   回执里点名这件事:{@code /setblock 0 64 0 stone}、{@code numen.work.dig({x = 1, y = 2, z = 3})}
     * @param go     放行之后接着做的;主人为它点过头时拿到的是交代了这一句的同一次调用
     */
    public void authorize(com.dwinovo.numen.permission.Action action, String what, Consumer<ServerSource> go) {
        Gate gate = Permission.gateFor(companion);
        Verdict verdict = gate.judgeLive(action, companion.serverLevel());
        switch (verdict.kind()) {
            case ALLOW -> go.accept(this);
            case DENY -> reply(CommandRunner.refused(what, verdict.reason()));
            case ASK -> PendingCommands.of(companion).await(this, what,
                    List.of(gate.consentItemLive(action, verdict, companion.serverLevel())), go);
        }
    }

    /** 送回这次调用的结果;主人为它点过头的,消息末尾交代那一句(和任务回执交代主人允许的是同一种写法)。 */
    @Override
    public void reply(String resultJson) {
        if (allowance == null) {
            reply.accept(resultJson);
            return;
        }
        JsonObject result = JsonParser.parseString(resultJson).getAsJsonObject();
        result.addProperty("message", result.get("message").getAsString() + " " + allowance + ".");
        reply.accept(result.toString());
    }
}
