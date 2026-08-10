package com.dwinovo.numen.client.command;

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

    @Override
    public String run(EntityAgentLoop loop, String args) {
        GoalState goal = loop.goal();
        if (args.isBlank()) {
            return goal == null
                    ? "还没有目标。直接说要做什么:/goal 把家门口那片林子清干净"
                    : "目标:" + goal.objective() + "(第 " + goal.turnsExecuted() + " 轮)";
        }
        if (args.toLowerCase(Locale.ROOT).equals("clear")) {
            if (goal == null) {
                return "本来就没有目标。";
            }
            loop.clearGoal(null);
            return "清掉了:" + goal.objective();
        }
        // 换掉一个还在跑的目标不静默:旧的那句原样说出来,想找回自己再贴一遍。
        String replaced = goal == null ? "" : "换掉了原来的:" + goal.objective() + "\n";
        loop.setGoal(GoalState.of(args, System.currentTimeMillis()));
        return replaced + "目标:" + args + "\n她会一轮接一轮做下去,/goal clear 或按停止键收工。";
    }
}
