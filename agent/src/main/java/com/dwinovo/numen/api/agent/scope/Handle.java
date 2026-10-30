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
}
