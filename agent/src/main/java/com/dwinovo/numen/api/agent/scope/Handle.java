package com.dwinovo.numen.api.agent.scope;

/**
 * 一次登记交回的句柄:{@link #close} 把这一条登记从它进的那张表里撤掉,只撤这一条。登记表都这样给:登记就拿一个句柄,
 * 撤回由登记表自己做干净(主流写法,VS Code 的 {@code Disposable}、Guava 的 {@code Closer})。
 *
 * <p>撤两次等于撤一次;撤掉之后同一条能再登记(那是一次新的登记,拿新的句柄)。
 */
@FunctionalInterface
public interface Handle {

    void close();

    /**
     * 只撤一次的句柄:{@code undo} 跑过之后再 {@code close} 什么也不做。登记表造句柄都用它,所以一个撤过的句柄晚到的第二次
     * {@code close} 不会撤掉后来别人(或同一个插件重登)登记的同一个名字。表里按键放的,{@code undo} 里还要认"表里现在的还是不是我登记的那条"
     * (比引用,不比 equals:两次登记出来的值可以 equals 相等)。
     */
    static Handle once(Runnable undo) {
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                undo.run();
            }
        };
    }
}
