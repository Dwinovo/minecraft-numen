package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.AppendLog;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.StoreException;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 远程端点的审计日志({@value #FILE},只追加):每次接入与每次工具调用一条。只记编号、令牌编号、工具名、成败、
 * 请求与回执的字节数——不记程序源码、事件正文、她说的话,也不记令牌本身。
 *
 * <p>日志按大小轮转:当前这份写到 {@link #MAX_BYTES} 就改名成 {@code .1}(原来的 {@code .1} 顺延成 {@code .2}……),
 * 只留最近 {@link #KEEP} 份旧的。轮转排在写盘的车道上、每条追加之前,和追加按提交顺序执行。
 */
final class McpAudit {

    static final String FILE = "mcp_audit.jsonl";

    /** 当前这份写到多大就轮转。 */
    static final long MAX_BYTES = 4L << 20;
    /** 留几份旧的。 */
    static final int KEEP = 4;

    private final AppendLog log;
    private final Path file;
    private final Io io;
    private final long maxBytes;
    private final int keep;

    McpAudit(Home home) {
        this(home, MAX_BYTES, KEEP);
    }

    McpAudit(Home home, long maxBytes, int keep) {
        this.file = home.file(FILE);
        this.io = home.io();
        this.log = new AppendLog(file, AppendLog.Format.of(1), io);
        this.maxBytes = maxBytes;
        this.keep = keep;
    }

    private void append(String type, JsonObject body) {
        io.write(this::rotateIfFull);
        log.append(type, body);
    }

    private void rotateIfFull() {
        try {
            if (!Files.exists(file) || Files.size(file) < maxBytes) {
                return;
            }
            Files.deleteIfExists(older(keep));
            for (int n = keep - 1; n >= 1; n--) {
                if (Files.exists(older(n))) {
                    Files.move(older(n), older(n + 1), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.move(file, older(1), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StoreException("轮转不了 " + file + ": " + e, e);
        }
    }

    private Path older(int n) {
        return file.resolveSibling(file.getFileName() + "." + n);
    }

    /** 一次接入({@code initialize}):对方自报的名字不是内容,是"谁接进来了"。 */
    void connected(String session, McpToken token, String client) {
        JsonObject body = new JsonObject();
        body.addProperty("session", session);
        body.addProperty("token", token.id());
        body.addProperty("role", token.role().name());
        body.addProperty("client", client);
        append("connect", body);
    }

    /** 认出了令牌却被拒(限流、会话开满)。 */
    void refused(String why, String tokenId) {
        JsonObject body = new JsonObject();
        body.addProperty("why", why);
        body.addProperty("token", tokenId);
        append("refused", body);
    }

    /** 没认出令牌的一波请求:同一来源地址在一个时间窗内合并成一条,记次数与首末时刻(毫秒)。 */
    void strangers(String address, String why, int count, long first, long last) {
        JsonObject body = new JsonObject();
        body.addProperty("why", why);
        body.addProperty("address", address);
        body.addProperty("count", count);
        body.addProperty("first", first);
        body.addProperty("last", last);
        append("refused", body);
    }

    /** 一次工具调用。{@code companion} 没有是 null。 */
    void called(String session, McpToken token, String tool, UUID companion, boolean ok, int bytesIn, int bytesOut) {
        JsonObject body = new JsonObject();
        body.addProperty("session", session);
        body.addProperty("token", token.id());
        body.addProperty("tool", tool);
        if (companion != null) {
            body.addProperty("companion", companion.toString());
        }
        body.addProperty("ok", ok);
        body.addProperty("in", bytesIn);
        body.addProperty("out", bytesOut);
        append("call", body);
    }
}
