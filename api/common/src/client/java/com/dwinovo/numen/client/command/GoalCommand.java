package com.dwinovo.numen.client.command;

import com.dwinovo.numen.agent.goal.GoalPrompts;
import com.dwinovo.numen.agent.goal.GoalState;
import com.dwinovo.numen.client.agent.EntityAgentLoop;

import java.util.Locale;

/**
 * {@code /goal} —— 一个跨轮次活着的长期目标。
 *
 * <p>只有两种形态:说一件事(设定),或者 {@code clear}(提前清掉)。<b>认不出的一律当目标
 * 正文</b>——这跟别的斜杠命令相反(那些认不出就报错),因为这条命令的主要用法就是直接说
 * 要干什么:{@code /goal 把家门口那片林子清干净}。
 */
final class GoalCommand implements ChatCommand {

    @Override
    public String name() {
        return "goal";
    }

    @Override
    public String description() {
        return "长期目标:她会一轮接一轮做下去";
    }

    @Override
    public String argHint() {
        return "<要做什么> | clear";
    }

    @Override
    public boolean touchesContext() {
        return true;
    }

    /** {@code clear} 的说法。主人想收工时脑子里冒出哪个词都算,不该让他猜对才行。 */
    private static final java.util.Set<String> CLEAR_WORDS =
            java.util.Set.of("clear", "stop", "off", "reset", "none", "cancel");

    @Override
    public String run(EntityAgentLoop loop, String args) {
        GoalState goal = loop.goal();
        if (args.isBlank()) {
            return status(goal);
        }
        if (CLEAR_WORDS.contains(args.toLowerCase(Locale.ROOT))) {
            if (goal == null) {
                return "本来就没有目标。";
            }
            loop.clearGoal(null);
            return "清掉了:" + goal.objective();
        }
        // 换掉一个还在跑的目标不静默:旧的那句原样说出来,想找回自己再贴一遍。
        String replaced = goal == null ? null : "换掉了原来的:" + goal.objective();
        loop.setGoal(GoalState.of(args, System.currentTimeMillis()),
                ChatCommands.PREFIX + name() + " " + args);
        // 目标本身不再复述一遍:聊天里已经有主人自己那条气泡,面板顶上也常驻一行。
        return replaced;
    }

    /** 无参时看的东西:条件、跑了多久、判了几轮、烧了多少、<b>评估器最近说还差什么</b>。 */
    private static String status(GoalState goal) {
        if (goal == null) {
            return "还没有目标。直接说要做什么:/goal 把家门口那片林子清干净";
        }
        StringBuilder sb = new StringBuilder("目标:").append(goal.objective())
                .append("\n跑了 ").append(GoalPrompts.elapsed(
                        goal.elapsedMs(System.currentTimeMillis())))
                .append(" · 第 ").append(goal.turnsExecuted()).append(" 轮")
                .append(" · ").append(goal.tokensUsed()).append(" token");
        if (goal.lastReason() != null) {
            sb.append("\n还差:").append(goal.lastReason());
        }
        return sb.toString();
    }
}
