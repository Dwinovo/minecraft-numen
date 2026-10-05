package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.agent.script.Program;

/**
 * 看着一段程序的每次调用派出去、回来。GameTest 与重启后再跑那一行据此认出每次调用派下的活、它的回执;不看就用 {@link #NONE}。
 * 三个时刻的线程不同:{@code dispatched} 在程序的线程上,{@code sent} 与 {@code replied} 在服务端主线程上。
 */
public interface CallObserver {

    /** 一次调用要派出去了。 */
    default void dispatched(String callId, Invocation invocation) {
    }

    /** 交出去了(服务端函数已经交给派发,它当场受理的活此刻在她的槽里;客户端函数请求已经发出);回来之前。 */
    default void sent(String callId) {
    }

    /** 回来了。 */
    default void replied(String callId, String reply) {
    }

    /**
     * 程序结束了,在服务端主线程上,先于答复交给客户端。这里的结局带着程序 {@code return} 的值原样和完整的错误值——它们不上网线,
     * 同进程的测试与工具从这里读。
     */
    default void ended(Program.Outcome outcome) {
    }

    /** 什么都不看。 */
    CallObserver NONE = new CallObserver() {
    };
}
