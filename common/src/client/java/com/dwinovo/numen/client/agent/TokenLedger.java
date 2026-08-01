package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
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
 * {@code config/numen/conversations/<uuid>.stats.json}。计费口径:每次请求
 * 都全量计费 prompt,所以按请求 total 累加才是真实开销;压缩调用同样计入。
 */
final class TokenLedger {

    private final UUID entityUuid;
    private long total;

    TokenLedger(UUID entityUuid) {
        this.entityUuid = entityUuid;
    }

    long total() {
        return total;
    }

    private Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("numen").resolve("conversations")
                .resolve(entityUuid + ".stats.json");
    }

    void load() {
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) return;
            JsonObject o = JsonParser.parseString(
                    Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("totalTokens")) total = Math.max(0, o.get("totalTokens").getAsLong());
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-entity#{}] token 统计读取失败: {}", entityUuid, ex.toString());
        }
    }

    /** 累加一次请求的计费等效 tokens 并写穿到 stats 文件(文件极小,每回合一写)。 */
    void add(long freshTokens) {
        if (freshTokens <= 0) return;
        total += freshTokens;
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, "{\"totalTokens\":" + total + "}", StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-entity#{}] token 统计写盘失败: {}", entityUuid, ex.toString());
        }
    }
}
