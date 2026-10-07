package com.dwinovo.numen.client.command;

import net.minecraft.client.resources.language.I18n;
import com.dwinovo.numen.data.ModLanguageData.Keys;
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
        return I18n.get(Keys.CMD_GOAL);
    }

    @Override
    public String argHint() {
        return I18n.get(Keys.CMD_GOAL_ARGS);
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
                return I18n.get(Keys.CMD_GOAL_NONE_TO_CLEAR);
            }
            loop.clearGoal(null);
            return I18n.get(Keys.CMD_GOAL_CLEARED, goal.objective());
        }
        // 换掉一个还在跑的目标不静默:旧的那句原样说出来,想找回自己再贴一遍。
        String replaced = goal == null ? null : I18n.get(Keys.CMD_GOAL_REPLACED, goal.objective());
        loop.setGoal(GoalState.of(args, System.currentTimeMillis()),
                ChatCommands.PREFIX + name() + " " + args);
        // 目标本身不再复述一遍:聊天里已经有主人自己那条气泡,面板顶上也常驻一行。
        return replaced;
    }

    /** 无参时看的东西:条件、跑了多久、判了几轮、烧了多少、<b>评估器最近说还差什么</b>。 */
    private static String status(GoalState goal) {
        if (goal == null) {
            return I18n.get(Keys.CMD_GOAL_EMPTY);
        }
        String status = I18n.get(Keys.CMD_GOAL_STATUS, goal.objective(),
                GoalPrompts.elapsed(goal.elapsedMs(System.currentTimeMillis())), goal.turnsExecuted(),
                goal.tokensUsed());
        return goal.lastReason() == null ? status
                : status + "\n" + I18n.get(Keys.CMD_GOAL_MISSING, goal.lastReason());
    }
}
