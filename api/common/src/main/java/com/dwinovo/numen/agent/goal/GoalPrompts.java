package com.dwinovo.numen.agent.goal;

/**
 * 目标的续跑提示。
 *
 * <h2>为什么每轮重拼一份完整的,而不是拼一次常驻</h2>
 * 目标是<b>当下的驱动力</b>,不是历史的一部分。每轮重拼,于是:进度(跑了几轮、干了多久)
 * 永远是新的;整理记忆把旧的那份丢掉也无所谓,下一轮自然又有一份;主人改了目标,立刻生效。
 * 拼一次常驻的话,这三件事都要额外写代码去维护。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class GoalPrompts {

    private GoalPrompts() {}

    /** 每轮收尾时注入的续跑块。 */
    public static String continuation(GoalState goal, long nowMs) {
        return """
                <goal-steering type="continuation">
                You have an active goal to work on. Continue making progress.

                ## Active Goal
                %s

                ## Status
                - Elapsed active time: %s
                - Tokens used: %d
                - Continuation turns executed: %d

                ## Instructions

                Continue working towards the goal. Do NOT narrow the scope of the goal — even if you
                cannot finish everything in one turn, keep the full objective and make as much progress
                as possible.

                When you believe the goal is fully achieved, call the `goal` tool with status=complete.
                Before doing so, perform a strict Completion Audit:

                ### Completion Audit
                1. Derive concrete requirements from the objective.
                2. Preserve the original scope — do not redefine success around what is already done.
                3. For every explicit requirement, identify authoritative evidence (a block you actually
                   placed, an item actually in the inventory, a tool result that actually succeeded).
                4. Treat a tool's success message as evidence only after confirming it covers the
                   requirement.
                5. Treat uncertain or indirect evidence as "not achieved".
                6. The audit must PROVE completion, not merely fail to find remaining work.

                If you cannot make progress, call the `goal` tool with status=blocked and a concrete
                reason. Before doing so, perform a Blocked Audit:

                ### Blocked Audit
                1. Name the specific thing that is missing or impossible.
                2. Confirm you have tried the alternatives available to you.
                3. State what the owner would have to provide for work to continue.
                4. A tool failing once is not blocked — blocked means no path forward exists.

                Resume working now.
                </goal-steering>"""
                .formatted(goal.objective(), elapsed(goal.activeElapsedMs(nowMs)),
                        goal.tokensUsed(), goal.turnsExecuted());
    }

    /** 人读的时长:比"3600000ms"有用。 */
    public static String elapsed(long ms) {
        long sec = Math.max(0L, ms / 1000L);
        if (sec < 60) {
            return sec + "s";
        }
        long min = sec / 60;
        if (min < 60) {
            return min + "min";
        }
        return (min / 60) + "h" + (min % 60) + "min";
    }
}
