package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.provider.CacheWaste;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 一个同伴的 token 消费台账,持久化于
 * 同伴家里的 {@code stats.json}(一个小文档)。四元累计(输入/输出/缓存读/
 * 缓存写)加一个新处理量的总数;压缩调用同样计入——它一样烧 token。
 *
 * <p>命中率不存:它只看<b>最近一轮</b>,累计命中率会被历史稀释,看不出"刚才那轮把
 * 缓存打穿了"。那个数活在内存里,见 {@link #latest()}。
 */
public final class TokenLedger {

    private final UUID entityUuid;
    private final JsonDoc doc;
    private long total;
    private Usage sum = Usage.ZERO;
    private Usage latest = Usage.ZERO;
    private final CacheWaste waste = new CacheWaste();

    /** @param doc 台账落盘的小文档({@code stats.json}):在哪、用哪条写车道由宿主给 */
    public TokenLedger(UUID entityUuid, JsonDoc doc) {
        this.entityUuid = entityUuid;
        this.doc = doc;
    }

    public long total() {
        return total;
    }

    /** 四元累计(跨会话)。 */
    public Usage sum() {
        return sum;
    }

    /** 最近一轮的用量——命中率取自它,不取累计。 */
    public Usage latest() {
        return latest;
    }

    /** 缓存重计费:本该命中却重新处理的量。本次运行内累计,不落盘。 */
    public CacheWaste waste() {
        return waste;
    }

    public void load() {
        try {
            doc.read().ifPresent(o -> {
                if (o.has("totalTokens")) total = Math.max(0, o.get("totalTokens").getAsLong());
                sum = new Usage(readLong(o, "input"), readLong(o, "output"),
                        readLong(o, "cacheRead"), readLong(o, "cacheWrite"));
            });
        } catch (RuntimeException ex) {
            Constants.LOG.warn("[numen-entity#{}] token 统计读取失败: {}", entityUuid, ex.toString());
        }
    }

    private static long readLong(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? Math.max(0, o.get(key).getAsLong()) : 0;
    }

    /**
     * 内核的事件里台账要接的:每一次模型调用的用量(对话、整理、目标评估都经 {@code ModelUsed} 这一条路来),
     * 以及历史换了之后缓存诊断清零——前缀本来就换了,下一轮的未命中不算"白付"。
     */
    public void on(com.dwinovo.numen.api.agent.loop.LoopEvent event) {
        switch (event) {
            case com.dwinovo.numen.api.agent.loop.LoopEvent.ModelUsed used -> add(used.usage());
            case com.dwinovo.numen.api.agent.loop.LoopEvent.TranscriptBoundary boundary -> {
                if (boundary.kind() != com.dwinovo.numen.api.agent.loop.LoopEvent.Boundary.HALT) {
                    waste.reset();
                }
            }
            default -> { }
        }
    }

    /** 累加一次请求的用量并写穿到 stats 文件(文件极小,每回合一写)。 */
    private void add(Usage u) {
        if (u == null) return;
        latest = u;
        waste.observe(u);
        sum = sum.plus(u);
        total += u.fresh();
        JsonObject o = new JsonObject();
        o.addProperty("totalTokens", total);
        o.addProperty("input", sum.input());
        o.addProperty("output", sum.output());
        o.addProperty("cacheRead", sum.cacheRead());
        o.addProperty("cacheWrite", sum.cacheWrite());
        doc.write(o);
    }
}
