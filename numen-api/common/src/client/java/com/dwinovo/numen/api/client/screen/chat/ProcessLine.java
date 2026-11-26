package com.dwinovo.numen.api.client.screen.chat;

import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.tool.ScriptTool;
import com.dwinovo.numen.script.ScriptEngine;

import java.util.List;

/**
 * 她连着的思考与工具调用(中间没开口)读成一段"过程":每一步是一次思考或一次调用,各有开始与结束的时刻。
 * 对话流把它画成一行安静的字,点开是按先后串起的步骤线;这里只管读出来的事实——一步用了多久、哪一步还在跑、
 * 最后一步成没成、整段用了多久、一次调用写的程序——不碰 Minecraft,单元测试直接喂步骤。
 *
 * <p>时刻都来自对话记录里每条记录本来带的时间戳,不另记:思考是它前一条记录到这一轮的间隔,调用是发出到结果回来的间隔,
 * 还在跑的一步用此刻减去它开始的时刻。成败只认 {@code ToolOutcome.failed},由调用方带进来,这里不猜字符串。
 */
public final class ProcessLine {

    private ProcessLine() {}

    /**
     * 过程里的一步。思考的 {@code call} 为 null、{@code receipt} 为 null;调用的 {@code thought} 为 null。
     *
     * @param thought 思考正文
     * @param call    这次调用
     * @param start   开始的时刻(毫秒);0 = 记录里没有时刻,这一步不计秒数
     * @param end     结束的时刻;0 = 没有(还在跑,或永远等不到结果)
     * @param running 还在跑(在流出来的思考、派发器还攥着的调用)
     * @param failed  这次调用失败了(被打断、死了也算)
     * @param receipt 这次调用的结果原文;还没有是 null
     */
    public record Step(String thought, LlmToolCall call, long start, long end, boolean running, boolean failed,
                       String receipt) {

        /** 一次落了库的思考:从它前一条记录到这一轮。 */
        public static Step thought(String text, long start, long end) {
            return new Step(text, null, start, end, false, false, null);
        }

        /** 还在往外流的思考:从它前一条记录起。 */
        public static Step liveThought(String text, long start) {
            return new Step(text, null, start, 0, true, false, null);
        }

        /** 一次调用:从发出的那一轮到结果回来;还没有结果时 {@code end} 是 0。 */
        public static Step call(LlmToolCall call, long start, long end, boolean running, boolean failed, String receipt) {
            return new Step(null, call, start, end, running, failed, receipt);
        }

        public boolean isCall() {
            return call != null;
        }

        /** 这一步用了多少毫秒:在跑的算到此刻;没有开始时刻、或结束了却没留下结束时刻的算 0。 */
        public long millis(long now) {
            if (start <= 0) return 0;
            long until = running ? now : end;
            return Math.max(0, until - start);
        }
    }

    /** 没有思考、只有一次调用:不包一层,画出来就是那一步。 */
    public static boolean single(List<Step> steps) {
        return steps.size() == 1 && steps.get(0).isCall();
    }

    /** 有没有调用:收起时写"已处理"还是"已思考"。 */
    public static boolean hasCalls(List<Step> steps) {
        return steps.stream().anyMatch(Step::isCall);
    }

    /** 眼下在做的那一步:还在流的思考(总是最后一步)先,否则第一个还在跑的调用;都收了是 null。 */
    public static Step current(List<Step> steps) {
        Step last = steps.get(steps.size() - 1);
        if (!last.isCall() && last.running()) return last;
        for (Step s : steps) {
            if (s.isCall() && s.running()) return s;
        }
        return null;
    }

    /** 最后一步是不是失败的调用:中途失败、后来成了的不算。 */
    public static boolean lastFailed(List<Step> steps) {
        Step last = steps.get(steps.size() - 1);
        return last.isCall() && last.failed();
    }

    /** 整段用了多少毫秒:第一步开始到最后一步结束(最后一步还在跑就算到此刻);第一步没有开始时刻是 0。 */
    public static long totalMillis(List<Step> steps, long now) {
        Step first = steps.get(0);
        Step last = steps.get(steps.size() - 1);
        if (first.start() <= 0) return 0;
        long until = last.running() ? now : last.end() > 0 ? last.end() : last.start();
        return Math.max(0, until - first.start());
    }

    /** 毫秒读作整秒(向下取整,走动的秒数与定格的秒数同一个读法)。 */
    public static long seconds(long millis) {
        return millis / 1000;
    }

    /**
     * 点开一次调用看的"代码":脚本工具是调用参数里的程序(读法归 {@link ScriptTool#parse});别的工具、或参数写坏了的
     * 脚本调用,是参数原文——那份原文正是她发出的东西。
     */
    public static String code(LlmToolCall call) {
        if (call.name().equals(ScriptEngine.IN_USE.toolName())) {
            try {
                return ScriptTool.parse(call.arguments()).code();
            } catch (IllegalArgumentException malformed) {
                return call.arguments();
            }
        }
        return call.arguments();
    }

    /** 这段思考的第一句(收起那一行上露出的):跳过开头的空行,没有内容是空串。 */
    public static String firstLine(String thought) {
        for (String line : thought.split("\\R")) {
            if (!line.isBlank()) return line.strip();
        }
        return "";
    }
}
