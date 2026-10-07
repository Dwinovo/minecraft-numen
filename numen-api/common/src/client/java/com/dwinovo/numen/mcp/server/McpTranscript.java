package com.dwinovo.numen.mcp.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 外接大脑模式的<b>现场缓冲</b>——主人此刻看得见的那场对话,不是任何人的账本。
 *
 * <p>模式开启期间面板聊天区画的就是它:主人的话(OWNER)、外脑替她说的话(SAY)、
 * 外脑的动作行(TOOL)。纯内存、每同伴限 {@value #CAP} 行、关游戏即弃——
 * 它和头顶气泡是同一性质:你在场所以看见了,没人承诺回放。对话历史的主人是
 * 外脑自己(它的会话就是它的记忆),这里一个字不落盘、不喂任何模型。
 *
 * <p>不带 companion 的调用(list_companions 等)进全局道,每个同伴的视图里都可见
 * ——那是模式层面的动静,主人在哪个同伴的屏上都该看得到。
 *
 * <h2>线程</h2>
 * OWNER/SAY 在客户端主线程写,TOOL 在 HTTP 线程写,渲染线程读快照——全部方法同步。
 */
public final class McpTranscript {

    /** 每同伴(以及全局道)各自的行数上限,满了丢最老的。 */
    public static final int CAP = 200;

    public enum Kind { OWNER, SAY, TOOL }

    /** 一行现场:{@code error} 只对 TOOL 有意义。 */
    public record Line(long ts, Kind kind, String text, boolean error) {}

    private static final Map<UUID, ArrayDeque<Line>> BY_COMPANION = new HashMap<>();
    private static final ArrayDeque<Line> GLOBAL = new ArrayDeque<>();

    private McpTranscript() {}

    /** 主人开口(面板/快捷对话/语音/桥接,一个咽喉:{@code enqueueOwnerWords})。 */
    public static synchronized void owner(UUID companion, String text) {
        push(lane(companion), Kind.OWNER, text, false);
    }

    /** 外脑替她说话(say 工具)。 */
    public static synchronized void say(UUID companion, String text) {
        push(lane(companion), Kind.SAY, text, false);
    }

    /** 外脑的一次动作(工具调用摘要行);{@code companion} 为 null 进全局道。 */
    public static synchronized void tool(UUID companion, String text, boolean error) {
        push(companion == null ? GLOBAL : lane(companion), Kind.TOOL, text, error);
    }

    /** 某同伴的现场(含全局道),按时间排好的只读快照。 */
    public static synchronized List<Line> view(UUID companion) {
        List<Line> out = new ArrayList<>(GLOBAL);
        ArrayDeque<Line> own = BY_COMPANION.get(companion);
        if (own != null) out.addAll(own);
        out.sort(java.util.Comparator.comparingLong(Line::ts));
        return out;
    }

    public static synchronized boolean isEmpty(UUID companion) {
        ArrayDeque<Line> own = BY_COMPANION.get(companion);
        return GLOBAL.isEmpty() && (own == null || own.isEmpty());
    }

    /** 测试用:清空全部现场。 */
    static synchronized void clearAll() {
        BY_COMPANION.clear();
        GLOBAL.clear();
    }

    private static ArrayDeque<Line> lane(UUID companion) {
        return BY_COMPANION.computeIfAbsent(companion, k -> new ArrayDeque<>());
    }

    private static void push(ArrayDeque<Line> lane, Kind kind, String text, boolean error) {
        if (text == null || text.isBlank()) return;
        lane.addLast(new Line(System.currentTimeMillis(), kind, text, error));
        while (lane.size() > CAP) lane.removeFirst();
    }
}
