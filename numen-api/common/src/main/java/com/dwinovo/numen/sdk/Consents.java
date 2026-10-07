package com.dwinovo.numen.sdk;

import com.dwinovo.numen.api.Internal;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.task.TaskRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 等主人点头的调用:一次调用要做的事({@link ServerCall#authorize})裁决出"要问"时,这次调用挂在这里,征询交给 {@link ConsentDesk},
 * 每刻读一次结论({@link #tick});允许就有值,拒绝、超时、被新的请求顶替都如实失败。
 *
 * <p>这样的一次调用不是身体上的活:它不占任务槽,身体照常做手上的事,等的只是这一次调用。身体那一头的几件事照任务槽的口径收尾——主人按
 * 停止({@link #stop})撤掉征询、这次调用说被叫停;身体离开世界同样;她死了({@link #drop})撤掉征询、不再有结果(那条调用已由死因结算)。
 */
@Internal
public final class Consents {

    /** 一次挂着的调用。它自己就是征询的作用域:主人允许的授权记在它名下,收场时一并清掉。 */
    private static final class Waiting {
        final String what;
        final List<ConsentItem> items;
        final Pending<Void> answer = Pending.create();
        ConsentDesk.Ticket ticket;

        Waiting(String what, List<ConsentItem> items) {
            this.what = what;
            this.items = items;
        }
    }

    private final NumenPlayer her;
    private final List<Waiting> waiting = new ArrayList<>();

    private Consents(NumenPlayer her) {
        this.her = her;
    }

    static Consents of(NumenPlayer her) {
        return her.state(Consents.class, () -> new Consents(her));
    }

    /** 问主人这一批事;答复到了才有结论。 */
    Pending<Void> await(String what, List<ConsentItem> items) {
        Waiting w = new Waiting(what, items);
        w.ticket = ConsentDesk.of(her).ask(w, items);
        waiting.add(w);
        return w.answer;
    }

    /** 每服务端刻一次:有结论的收场。 */
    public static void tick(NumenPlayer her) {
        Consents pending = of(her);
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
                w.answer.report(answer.allowance(w.items) + ".");
                w.answer.complete(null);
            } else {
                w.answer.fail(ServerCall.refused(w.what, answer.refusal(w.items)));
            }
        }
    }

    /** 主人按了停止,或身体要离开世界:撤掉征询(主人看到是谁叫停的),这些调用说被谁叫停、没有执行。 */
    public static void stop(NumenPlayer her, TaskRecord.StopCause cause) {
        for (Waiting w : of(her).drain(cause.withdrawal())) {
            w.answer.interrupt(cause.words() + " — " + w.what + " did not run");
        }
    }

    /** 她死了:撤掉征询,不再有结论——那条调用已由死因结算。 */
    public static void drop(NumenPlayer her) {
        of(her).drain(ConsentDesk.Withdrawal.DIED);
    }

    private List<Waiting> drain(ConsentDesk.Withdrawal why) {
        List<Waiting> all = List.copyOf(waiting);
        waiting.clear();
        for (Waiting w : all) {
            ConsentDesk.of(her).release(w, why);
        }
        return all;
    }
}
