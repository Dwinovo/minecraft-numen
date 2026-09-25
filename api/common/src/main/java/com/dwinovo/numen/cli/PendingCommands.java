package com.dwinovo.numen.cli;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.api.Internal;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 等主人点头的指令:执行入口裁决出"要问"时,这次调用挂在这里,征询交给 {@link ConsentDesk},每刻读一次结论
 * ({@link #tick});允许就接着执行,拒绝、超时、被新的请求顶替都如实回执。
 *
 * <p>一行指令不是身体上的活:它不占任务槽,身体照常做手上的事,等的只是这一次调用。所以它不进任务槽,而是和征询一起
 * 挂在身体上;身体那一头的几件事照任务槽的口径收尾——主人按停止({@link #stop})撤掉征询、回执说被叫停;身体离开世界
 * 同样;她死了({@link #drop})撤掉征询、不回执(那条调用已由死因结算)。
 */
@Internal
public final class PendingCommands {

    /** 一次挂着的调用。它自己就是征询的作用域:主人允许的授权记在它名下,收场时一并清掉。 */
    private static final class Waiting {
        final ServerSource call;
        final String line;
        final List<ConsentItem> items;
        final Consumer<ServerSource> go;
        ConsentDesk.Ticket ticket;

        Waiting(ServerSource call, String line, List<ConsentItem> items, Consumer<ServerSource> go) {
            this.call = call;
            this.line = line;
            this.items = items;
            this.go = go;
        }
    }

    private final List<Waiting> waiting = new ArrayList<>();

    private PendingCommands() {}

    static PendingCommands of(NumenPlayer her) {
        return her.state(PendingCommands.class, PendingCommands::new);
    }

    /** 问主人这一批事,答复到了再决定这次调用怎么走。 */
    void await(ServerSource call, String line, List<ConsentItem> items, Consumer<ServerSource> go) {
        Waiting w = new Waiting(call, line, items, go);
        w.ticket = ConsentDesk.of(call.companion()).ask(w, items);
        waiting.add(w);
    }

    /** 每服务端 tick 一次,排在登记处之后:有结论的收场,允许的接着执行。 */
    public static void tick(NumenPlayer her) {
        PendingCommands pending = of(her);
        if (pending.waiting.isEmpty()) {
            return;
        }
        for (Waiting w : List.copyOf(pending.waiting)) {
            ConsentAnswer answer = w.ticket.poll();
            if (answer == null) {
                continue;
            }
            pending.waiting.remove(w);
            // 它的请求刚有了结论,已经不挂着了:这里只清它名下的授权
            ConsentDesk.of(her).release(w, ConsentDesk.Withdrawal.UNNEEDED);
            if (answer.allowed()) {
                proceed(w, answer);
            } else {
                w.call.reply(CommandRunner.refused(w.line, answer.refusal(w.items)));
            }
        }
    }

    /**
     * 主人点了头,接着执行。这一步是从服务器刻里走的,不在任何一次调用的栈上:处理函数抛出的错误在这里收成这次调用的
     * 失败回执,和工具入口({@code NumenTool.serve})收下调用自己的错误是同一个口径。
     */
    private static void proceed(Waiting w, ConsentAnswer answer) {
        try {
            w.go.accept(w.call.allowed(answer.allowance(w.items)));
        } catch (RuntimeException e) {
            Constants.LOG.warn("[numen-cli] /{} failed after the owner allowed it", w.line, e);
            w.call.reply(TaskResult.fail("/" + w.line + " failed: " + e.getMessage()).toJson());
        }
    }

    /** 主人按了停止,或身体要离开世界:撤掉征询(主人看到是谁叫停的),这些调用回执说被谁叫停、没有执行。 */
    public static void stop(NumenPlayer her, TaskRecord.StopCause cause) {
        for (Waiting w : of(her).drain(cause.withdrawal())) {
            w.call.reply(TaskResult.cancelled(cause.words() + " — /" + w.line + " did not run").toJson());
        }
    }

    /** 她死了:撤掉征询,不回执——那条调用已由死因结算。 */
    public static void drop(NumenPlayer her) {
        of(her).drain(ConsentDesk.Withdrawal.DIED);
    }

    /** 全部取出,撤掉各自挂着的征询与授权;{@code why} 是主人看到的撤回原因。 */
    private List<Waiting> drain(ConsentDesk.Withdrawal why) {
        List<Waiting> all = List.copyOf(waiting);
        waiting.clear();
        for (Waiting w : all) {
            ConsentDesk.of(w.call.companion()).release(w, why);
        }
        return all;
    }
}
