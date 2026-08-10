package com.dwinovo.numen.agent.goal;

import com.google.gson.JsonObject;

/**
 * 一个长期目标。
 *
 * <h2>它解决什么</h2>
 * 一轮对话说完就散了。目标是<b>跨轮次活着</b>的那一件事:她每停下来一次,客户端就把目标
 * 连同进度重新递到她面前,直到做完。
 *
 * <h2>没有状态机</h2>
 * 目标只有"在"和"不在"两种。停下来的四种方式——她报完成、连撞三次墙、跑够轮次、主人
 * 喊停——<b>结果都是清掉</b>,区别只在告诉主人的那句话。
 *
 * <p>不留"暂停着的目标"这种中间态:想继续就再说一遍 {@code /goal ...},成本本来就是一句
 * 话;而中间态要配一整套动词(resume / continue / status)才用得起来,那些动词又各自要回答
 * "从哪儿能到哪儿"。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class GoalState {

    /** 连着撞同一堵墙几次就放弃。一次挡住只是这一步没走通,换个法子还能继续。 */
    public static final int BLOCKED_CONSECUTIVE_THRESHOLD = 3;
    /** 一个目标最多自动续这么多轮。防的是"她以为没做完"导致的无限循环。 */
    public static final int MAX_GOAL_TURNS = 150;
    /** 目标正文上限。 */
    public static final int MAX_OBJECTIVE_CHARS = 4000;

    private String objective;
    private long startedAt;
    private int turnsExecuted;
    private long tokensUsed;
    private int blockedAttempts;
    private String lastBlockReason;

    private GoalState() {}

    public static GoalState of(String objective, long nowMs) {
        GoalState g = new GoalState();
        String s = objective == null ? "" : objective.strip();
        g.objective = s.length() > MAX_OBJECTIVE_CHARS ? s.substring(0, MAX_OBJECTIVE_CHARS) : s;
        g.startedAt = nowMs;
        return g;
    }

    public String objective() {
        return objective;
    }

    public int turnsExecuted() {
        return turnsExecuted;
    }

    public long tokensUsed() {
        return tokensUsed;
    }

    public int blockedAttempts() {
        return blockedAttempts;
    }

    public String lastBlockReason() {
        return lastBlockReason;
    }

    /** 从设定到现在多久。中途没有暂停这回事,所以就是一个减法。 */
    public long elapsedMs(long nowMs) {
        return Math.max(0L, nowMs - startedAt);
    }

    /** 续跑额度还有没有。 */
    public boolean hasTurnsLeft() {
        return turnsExecuted < MAX_GOAL_TURNS;
    }

    /** 记一轮续跑。 */
    public void countTurn() {
        turnsExecuted++;
    }

    /** 记一次请求烧掉的 token。 */
    public void addTokens(long n) {
        if (n > 0) {
            tokensUsed += n;
        }
    }

    /**
     * 她报告被挡住了。
     *
     * @return {@code true} = 同一个理由已经撞够 {@value #BLOCKED_CONSECUTIVE_THRESHOLD} 次,
     *         该放弃了(清掉目标并告诉主人)
     */
    public boolean reportBlocked(String reason) {
        String r = reason == null ? "" : reason.strip();
        blockedAttempts = r.equals(lastBlockReason) ? blockedAttempts + 1 : 1;
        lastBlockReason = r;
        return blockedAttempts >= BLOCKED_CONSECUTIVE_THRESHOLD;
    }

    // ---- 落盘 ----

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("objective", objective);
        o.addProperty("startedAt", startedAt);
        o.addProperty("turnsExecuted", turnsExecuted);
        o.addProperty("tokensUsed", tokensUsed);
        o.addProperty("blockedAttempts", blockedAttempts);
        if (lastBlockReason != null) {
            o.addProperty("lastBlockReason", lastBlockReason);
        }
        return o;
    }

    /** 从落盘记录还原;正文为空视为没有目标,返回 {@code null}。 */
    public static GoalState fromJson(JsonObject o) {
        if (o == null) {
            return null;
        }
        String objective = str(o, "objective");
        if (objective.isBlank()) {
            return null;
        }
        GoalState g = new GoalState();
        g.objective = objective;
        g.startedAt = num(o, "startedAt");
        g.turnsExecuted = (int) num(o, "turnsExecuted");
        g.tokensUsed = num(o, "tokensUsed");
        g.blockedAttempts = (int) num(o, "blockedAttempts");
        g.lastBlockReason = o.has("lastBlockReason") && !o.get("lastBlockReason").isJsonNull()
                ? o.get("lastBlockReason").getAsString() : null;
        return g;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long num(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? Math.max(0L, o.get(key).getAsLong()) : 0L;
    }
}
