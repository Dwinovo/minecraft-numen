package com.dwinovo.numen.api.sdk;

/**
 * 一次客户端函数调用能碰到的、主人客户端上她的大脑的东西(她的模块、她的札记……):由持有她大脑的一方({@code LoopHost})给,
 * 函数不到任何静态的登记处去找。按类型问,没有这样东西是编程错误。
 */
public interface ClientContext {

    /**
     * 她的大脑里这种类型的那一样东西。
     *
     * @throws IllegalArgumentException 大脑里没有这种类型的东西
     */
    <T> T of(Class<T> type);
}
