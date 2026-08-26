package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.provider.CacheWaste;
import com.dwinovo.numen.agent.provider.Usage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 一个同伴的 token 消费台账,持久化于
 * {@code config/numen/conversations/<uuid>.stats.json}。四元累计(输入/输出/缓存读/
 * 缓存写)加一个新处理量的总数;压缩调用同样计入——它一样烧 token。
 *
 * <p>命中率不存:它只看<b>最近一轮</b>,累计命中率会被历史稀释,看不出"刚才那轮把
 * 缓存打穿了"。那个数活在内存里,见 {@link #latest()}。
 */
final class TokenLedger {

    private final UUID entityUuid;
    private long total;
    private Usage sum = Usage.ZERO;
    private Usage latest = Usage.ZERO;
    private final CacheWaste waste = new CacheWaste();

    TokenLedger(UUID entityUuid) {
        this.entityUuid = entityUuid;
    }

    long total() {
        return total;
    }

    /** 四元累计(跨会话)。 */
    Usage sum() {
        return sum;
    }

    /** 最近一轮的用量——命中率取自它,不取累计。 */
    Usage latest() {
        return latest;
    }

    /** 缓存重计费:本该命中却重新处理的量。本次运行内累计,不落盘。 */
    CacheWaste waste() {
        return waste;
    }

    private Path file() {
        return CompanionHome.stats(entityUuid);
    }

    void load() {
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) return;
            JsonObject o = JsonParser.parseString(
                    Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("totalTokens")) total = Math.max(0, o.get("totalTokens").getAsLong());
            sum = new Usage(readLong(o, "input"), readLong(o, "output"),
                    readLong(o, "cacheRead"), readLong(o, "cacheWrite"));
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-entity#{}] token 统计读取失败: {}", entityUuid, ex.toString());
        }
    }

    private static long readLong(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? Math.max(0, o.get(key).getAsLong()) : 0;
    }

    /** 累加一次请求的用量并写穿到 stats 文件(文件极小,每回合一写)。 */
    void add(Usage u) {
        if (u == null) return;
        latest = u;
        waste.observe(u);
        sum = sum.plus(u);
        total += u.fresh();
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("totalTokens", total);
            o.addProperty("input", sum.input());
            o.addProperty("output", sum.output());
            o.addProperty("cacheRead", sum.cacheRead());
            o.addProperty("cacheWrite", sum.cacheWrite());
            Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-entity#{}] token 统计写盘失败: {}", entityUuid, ex.toString());
        }
    }
}
