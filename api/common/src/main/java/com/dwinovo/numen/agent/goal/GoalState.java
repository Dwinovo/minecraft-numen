package com.dwinovo.numen.agent.goal;

import com.google.gson.JsonObject;

/**
 * 一个长期目标的全部状态。
 *
 * <h2>它解决什么</h2>
 * 一轮对话说完就散了。目标是<b>跨轮次活着</b>的那一件事:每轮收尾自动续上,直到她自己
 * 判定做完、或者撞墙、或者主人喊停。
 *
 * <h2>计时分两半</h2>
 * {@code accumulatedActiveMs} 是已经攒下的活跃时长,{@code startTime} 是当前这一段的起点。
 * 暂停时把当前段结算进累计、清掉起点;恢复时重新起点。所以"跑了多久"问的是<b>干活的时长</b>,
 * 不是"这个目标是几小时前建的"——挂机不该算进去。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class GoalState {

    /** 连着撞同一堵墙几次就判定卡住。 */
    public static final int BLOCKED_CONSECUTIVE_THRESHOLD = 3;
    /** 一个目标最多自动续这么多轮。防的是"她以为没做完"导致的无限循环。 */
    public static final int MAX_GOAL_TURNS = 150;
    /** 目标正文上限。 */
    public static final int MAX_OBJECTIVE_CHARS = 4000;

    private String objective;
    private GoalStatus status;
    private long startTime;
    private Long pausedAt;
    private long accumulatedActiveMs;
    private int blockedAttempts;
    private String lastBlockReason;
    private long createdAt;
    private long updatedAt;
    private int turnsExecuted;
    private long tokensUsed;

    private GoalState() {}

    /** 新建一个在跑的目标。 */
    public static GoalState of(String objective, long nowMs) {
        GoalState g = new GoalState();
        g.objective = trim(objective);
        g.status = GoalStatus.ACTIVE;
        g.startTime = nowMs;
        g.createdAt = nowMs;
        g.updatedAt = nowMs;
        return g;
    }

    private static String trim(String objective) {
        String s = objective == null ? "" : objective.strip();
        return s.length() > MAX_OBJECTIVE_CHARS ? s.substring(0, MAX_OBJECTIVE_CHARS) : s;
    }

    // ---- 看 ----

    public String objective() {
        return objective;
    }

    public GoalStatus status() {
        return status;
    }

    public boolean isActive() {
        return status == GoalStatus.ACTIVE;
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

    public long createdAt() {
        return createdAt;
    }

    /** 真正在干活的时长(暂停期间不计)。 */
    public long activeElapsedMs(long nowMs) {
        long live = status == GoalStatus.ACTIVE && startTime > 0 && nowMs > startTime
                ? nowMs - startTime : 0L;
        return Math.max(0L, accumulatedActiveMs + live);
    }

    /** 续跑额度还有没有。 */
    public boolean hasTurnsLeft() {
        return turnsExecuted < MAX_GOAL_TURNS;
    }

    // ---- 转换 ----

    /** 记一轮续跑。 */
    public void countTurn(long nowMs) {
        turnsExecuted++;
        updatedAt = nowMs;
    }

    /** 记一次请求烧掉的 token。 */
    public void addTokens(long n, long nowMs) {
        if (n > 0) {
            tokensUsed += n;
            updatedAt = nowMs;
        }
    }

    public boolean pause(long nowMs) {
        if (status != GoalStatus.ACTIVE) {
            return false;
        }
        settleActiveSegment(nowMs);
        pausedAt = nowMs;
        status = GoalStatus.PAUSED;
        updatedAt = nowMs;
        return true;
    }

    /** 从暂停/卡住恢复。做完的目标接不回来——那是终点。 */
    public boolean resume(long nowMs) {
        if (status != GoalStatus.PAUSED && status != GoalStatus.BLOCKED) {
            return false;
        }
        status = GoalStatus.ACTIVE;
        startTime = nowMs;
        pausedAt = null;
        blockedAttempts = 0;
        lastBlockReason = null;
        updatedAt = nowMs;
        return true;
    }

    /** 跑够轮次之后再放一轮额度。 */
    public boolean continueFromMaxTurns(long nowMs) {
        if (status != GoalStatus.MAX_TURNS) {
            return false;
        }
        status = GoalStatus.ACTIVE;
        startTime = nowMs;
        pausedAt = null;
        turnsExecuted = 0;
        updatedAt = nowMs;
        return true;
    }

    public boolean complete(long nowMs) {
        if (status == GoalStatus.COMPLETE) {
            return false;
        }
        settleActiveSegment(nowMs);
        status = GoalStatus.COMPLETE;
        updatedAt = nowMs;
        return true;
    }

    public boolean markMaxTurns(long nowMs) {
        if (status != GoalStatus.ACTIVE) {
            return false;
        }
        settleActiveSegment(nowMs);
        status = GoalStatus.MAX_TURNS;
        updatedAt = nowMs;
        return true;
    }

    /**
     * 她报告被挡住了。
     *
     * <p>同一个理由连着 {@value #BLOCKED_CONSECUTIVE_THRESHOLD} 次才真的判卡住——
     * 一次挡住可能只是这一步没走通,换个法子还能继续;每次都停下来问主人才是烦人。
     *
     * @return true = 这次真的停了
     */
    public boolean reportBlocked(String reason, long nowMs) {
        String r = reason == null ? "" : reason.strip();
        blockedAttempts = r.equals(lastBlockReason) ? blockedAttempts + 1 : 1;
        lastBlockReason = r;
        updatedAt = nowMs;
        if (blockedAttempts < BLOCKED_CONSECUTIVE_THRESHOLD || status != GoalStatus.ACTIVE) {
            return false;
        }
        settleActiveSegment(nowMs);
        status = GoalStatus.BLOCKED;
        return true;
    }

    /** 把当前这一段活跃时长结算进累计。 */
    private void settleActiveSegment(long nowMs) {
        if (status == GoalStatus.ACTIVE && startTime > 0 && nowMs > startTime) {
            accumulatedActiveMs += nowMs - startTime;
        }
        startTime = 0;
    }

    // ---- 落盘 ----

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("objective", objective);
        o.addProperty("status", status.key());
        o.addProperty("startTime", startTime);
        if (pausedAt != null) o.addProperty("pausedAt", pausedAt);
        o.addProperty("accumulatedActiveMs", accumulatedActiveMs);
        o.addProperty("blockedAttempts", blockedAttempts);
        if (lastBlockReason != null) o.addProperty("lastBlockReason", lastBlockReason);
        o.addProperty("createdAt", createdAt);
        o.addProperty("updatedAt", updatedAt);
        o.addProperty("turnsExecuted", turnsExecuted);
        o.addProperty("tokensUsed", tokensUsed);
        return o;
    }

    /** 从落盘记录还原;正文为空视为没有目标,返回 null。 */
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
        g.status = GoalStatus.parse(str(o, "status"), GoalStatus.PAUSED);
        g.startTime = num(o, "startTime");
        g.pausedAt = o.has("pausedAt") && !o.get("pausedAt").isJsonNull()
                ? o.get("pausedAt").getAsLong() : null;
        g.accumulatedActiveMs = num(o, "accumulatedActiveMs");
        g.blockedAttempts = (int) num(o, "blockedAttempts");
        g.lastBlockReason = o.has("lastBlockReason") && !o.get("lastBlockReason").isJsonNull()
                ? o.get("lastBlockReason").getAsString() : null;
        g.createdAt = num(o, "createdAt");
        g.updatedAt = num(o, "updatedAt");
        g.turnsExecuted = (int) num(o, "turnsExecuted");
        g.tokensUsed = num(o, "tokensUsed");
        return g;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long num(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? Math.max(0L, o.get(key).getAsLong()) : 0L;
    }
}
