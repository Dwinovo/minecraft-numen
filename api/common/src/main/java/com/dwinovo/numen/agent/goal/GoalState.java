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

    /**
     * 一个目标最多推她这么多轮。
     *
     * <p>兜底,不是主要机制:想精确限制直接写进条件("挖 64 个铁锭,或者跑 20 轮就收工"),
     * 评估器判得出来。留这道硬线是因为她比命令行更容易陷进去,而主人可能根本没在看屏幕。
     *
     * <p>为什么是 30 而不是更大:她一轮的主请求是 18k~33k token(38 个工具的 schema +
     * 系统提示 + 全部历史),30 轮已经六十万起。到顶不是失败,是<b>停下来让主人看一眼</b>
     * 再决定要不要续——那比闷头烧下去有用。
     */
    public static final int MAX_GOAL_TURNS = 30;
    /** 目标正文上限。 */
    public static final int MAX_OBJECTIVE_CHARS = 4000;

    private String objective;
    private long startedAt;
    private int turnsExecuted;
    private long tokensUsed;
    /** 评估器上一次给的理由。主人靠它知道"她接下来要朝什么努力"。 */
    private String lastReason;

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

    /** 评估器上一次说还差什么;还没评过则 {@code null}。 */
    public String lastReason() {
        return lastReason;
    }

    public void setLastReason(String reason) {
        this.lastReason = reason == null || reason.isBlank() ? null : reason.strip();
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

    // ---- 落盘 ----

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("objective", objective);
        o.addProperty("startedAt", startedAt);
        o.addProperty("turnsExecuted", turnsExecuted);
        o.addProperty("tokensUsed", tokensUsed);
        if (lastReason != null) {
            o.addProperty("lastReason", lastReason);
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
        g.lastReason = o.has("lastReason") && !o.get("lastReason").isJsonNull()
                ? o.get("lastReason").getAsString() : null;
        return g;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long num(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? Math.max(0L, o.get(key).getAsLong()) : 0L;
    }
}
