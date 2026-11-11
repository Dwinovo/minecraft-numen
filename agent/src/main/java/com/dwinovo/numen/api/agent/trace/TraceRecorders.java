package com.dwinovo.numen.api.agent.trace;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 这个进程里各只同伴的轨迹:按她认出一个 {@link TraceRecorder}。
 *
 * <p>为什么要这张表:轨迹是<b>机器</b>(记账),记账的那些类在 Numen API 里;而寻路是<b>内容</b>,
 * 只看得见插件那一侧的公开面。寻路要报"为什么走不过去"时手上只有那具身体,拿不到她的大脑——
 * 于是登记在这里,查的人按 UUID 取。
 *
 * <p>登记的是她自己那条({@link TraceRecorder} 一次服务端运行一份),不是一次性的事件口:
 * 调用方拿到就能写,不必每刻重新登记。
 *
 * <p>线程:任何线程。
 */
public final class TraceRecorders {

    private static final Map<UUID, TraceRecorder> RECORDERS = new ConcurrentHashMap<>();

    private TraceRecorders() {}

    /** 让这只同伴的轨迹可以被按 UUID 找到。重建大脑时再登记一次,后登记的顶掉前一份。 */
    public static void put(UUID companion, TraceRecorder trace) {
        RECORDERS.put(companion, trace);
    }

    /** 这只同伴此刻的轨迹;还没有(她的大脑不在)是空。 */
    public static Optional<TraceRecorder> of(UUID companion) {
        return Optional.ofNullable(RECORDERS.get(companion));
    }

    /** 她不再是这个进程里的同伴了:这个名字不再指向任何轨迹。 */
    public static void forget(UUID companion) {
        RECORDERS.remove(companion);
    }

    /** 服务端停了:整张表作废(下一次运行是新的 session,新的记录器)。 */
    public static void forgetAll() {
        RECORDERS.clear();
        TraceRecorder.forgetSessions();
    }
}
