package com.dwinovo.numen.client.command;

import com.dwinovo.numen.agent.goal.GoalPrompts;
import com.dwinovo.numen.agent.goal.GoalState;
import com.dwinovo.numen.client.agent.EntityAgentLoop;

import java.util.Locale;

/**
 * {@code /goal} —— 一个跨轮次活着的长期目标。
 *
 * <p>没有子命令对象:动作是对参数做前缀匹配分出来的,认不出的<b>一律当成目标正文</b>。
 * 这跟别的斜杠命令相反(那些认不出就报错),因为这条命令的主要用法就是直接说要干什么
 * ——{@code /goal 把家门口那片林子清干净}。
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
        return "<要做什么> | status | pause | resume | continue | complete | clear";
    }

    @Override
    public boolean touchesContext() {
        return true;
    }

    @Override
    public String run(EntityAgentLoop loop, String args) {
        long now = System.currentTimeMillis();
        GoalState goal = loop.goal();
        String verb = args.toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "", "status" -> status(goal, now);
            case "clear" -> clear(loop, goal);
            case "pause" -> transition(loop, goal, goal != null && goal.pause(now),
                    "已暂停,/goal resume 接着来", "现在这个目标不是在跑的状态");
            case "resume" -> transition(loop, goal, goal != null && goal.resume(now),
                    "接着来了", "只有暂停或卡住的目标能恢复");
            case "continue" -> transition(loop, goal, goal != null && goal.continueFromMaxTurns(now),
                    "又放了一轮额度,接着来", "只有跑够轮次停下的目标需要这个");
            case "complete" -> transition(loop, goal, goal != null && goal.complete(now),
                    "标记为做完了", "它已经是完成状态了");
            default -> setObjective(loop, goal, args, now);
        };
    }

    private static String status(GoalState goal, long now) {
        if (goal == null) {
            return "还没有目标。直接说要做什么:/goal 把家门口那片林子清干净";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("目标:").append(goal.objective())
                .append("\n状态:").append(goal.status().label())
                .append("  已干:").append(GoalPrompts.elapsed(goal.activeElapsedMs(now)))
                .append("  续了 ").append(goal.turnsExecuted()).append(" 轮")
                .append("  烧了 ").append(goal.tokensUsed()).append(" token");
        if (goal.lastBlockReason() != null && !goal.lastBlockReason().isBlank()) {
            sb.append("\n卡在:").append(goal.lastBlockReason())
                    .append("(第 ").append(goal.blockedAttempts()).append(" 次)");
        }
        return sb.toString();
    }

    private static String clear(EntityAgentLoop loop, GoalState goal) {
        if (goal == null) {
            return "本来就没有目标。";
        }
        loop.setGoal(null);
        return "清掉了:" + goal.objective();
    }

    /** 状态机说行就落盘,说不行就把为什么说清楚。 */
    private static String transition(EntityAgentLoop loop, GoalState goal,
                                     boolean changed, String ok, String refused) {
        if (goal == null) {
            return "还没有目标。";
        }
        if (!changed) {
            return refused + "(现在是:" + goal.status().label() + ")";
        }
        loop.goalChanged();
        return ok;
    }

    private static String setObjective(EntityAgentLoop loop, GoalState goal,
                                       String objective, long now) {
        // 换掉一个还没做完的目标不静默:旧的那句原样说出来,想找回就自己再贴一遍。
        String replaced = goal != null && goal.status() != com.dwinovo.numen.agent.goal.GoalStatus.COMPLETE
                ? "换掉了原来的:" + goal.objective() + "\n" : "";
        loop.setGoal(GoalState.of(objective, now));
        return replaced + "目标:" + objective + "\n她会一轮接一轮做下去,/goal pause 可以喊停。";
    }
}
