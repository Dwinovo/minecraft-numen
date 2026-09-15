package com.dwinovo.numen.agent.http;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次(或一串)请求的取消令牌。谁发起请求谁持有它;{@link #cancel} 之后,挂在它上面的请求
 * 关掉连接、不再回调、不再重试。
 *
 * <p>取消是单向的:取消过就一直是取消的。登记的动作在 {@link #cancel} 的调用线程上同步执行一次,
 * 之后登记的当场执行——不存在"取消了但有人没听见"的窗口。
 *
 * <p>线程安全:传输层在 HTTP 线程上登记和撤销,持有者在主线程上取消。
 */
public final class CancelToken {

    private final List<Runnable> actions = new ArrayList<>();
    private boolean cancelled;

    /** 取消。重复调用无事发生。 */
    public void cancel() {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            toRun = List.copyOf(actions);
            actions.clear();
        }
        for (Runnable action : toRun) {
            action.run();
        }
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /**
     * 登记取消时要做的事;已经取消了就当场做。
     *
     * @return 撤销这次登记的口子。请求正常结束时调它——一个令牌可能陪着一整串请求,
     *         不撤销的话结束了的请求会一直挂在上面
     */
    public Runnable onCancel(Runnable action) {
        synchronized (this) {
            if (!cancelled) {
                actions.add(action);
                return () -> {
                    synchronized (this) {
                        actions.remove(action);
                    }
                };
            }
        }
        action.run();
        return () -> { };
    }
}
