package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 令牌库:发、认、列、作废。落在服务器的家里({@value #FILE}),只存哈希——磁盘上的文件被看到也换不到令牌。
 * 令牌是 256 位的随机数,不是人想出来的口令,所以单次 SHA-256 足够,不需要加盐的慢哈希。
 *
 * <p>文件读不了时整库不可用({@link #unavailable}):认人一律落空,发与作废抛异常,那份文件原样不动。
 *
 * <p>线程:任何线程(HTTP 线程认人、主线程发与作废),状态在 this 的锁里。
 */
public final class McpTokens {

    public static final String FILE = "mcp_tokens.json";
    /** 明文令牌的前缀:一眼认出是 Numen 的,泄露扫描也认得。 */
    public static final String PREFIX = "numen_";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 刚发出的一张:明文只此一次。 */
    public record Issued(McpToken token, String secret) {}

    private final JsonDoc doc;
    private final LongSupplier clock;
    private final Map<String, McpToken> byHash = new LinkedHashMap<>();
    /** 令牌库读不了:文件原样留着不动,这一局整库按"不可用"处理;读得了是 null。 */
    private Unreadable unreadable;

    /** 令牌库读不了的文件与原因。 */
    public record Unreadable(Path file, String reason) {}

    public McpTokens(Home home, LongSupplier clock) {
        this.doc = new JsonDoc(home.file(FILE), JsonDoc.Format.of(1), home.io());
        this.clock = clock;
        load();
    }

    private void load() {
        try {
            JsonObject root = doc.read().orElse(null);
            if (root == null || !root.has("tokens")) {
                return;
            }
            for (JsonElement el : root.getAsJsonArray("tokens")) {
                McpToken token = fromJson(el.getAsJsonObject());
                byHash.put(token.hash(), token);
            }
        } catch (RuntimeException e) {
            // 读不了的那份原样留着(不覆盖、不删),这一局不认任何令牌、也不发不作废,免得下一次写把它盖掉;
            // 服主修好或挪走文件,下次起服再读
            byHash.clear();
            unreadable = new Unreadable(doc.file(), e.toString());
            Constants.LOG.warn("[numen-mcp] 令牌库 {} 读不了,原样留着不动,这一局拒绝所有令牌: {}", doc.file(), e.toString());
        }
    }

    /** 令牌库这一局能不能用;不能用时说明文件在哪、为什么。 */
    public synchronized Optional<Unreadable> unavailable() {
        return Optional.ofNullable(unreadable);
    }

    private void requireUsable() {
        if (unreadable != null) {
            throw new IllegalStateException("token store unreadable: " + unreadable.file());
        }
    }

    /** 发一张。 */
    public synchronized Issued issue(UUID owner, McpToken.Role role, McpToken.Scope scope, String label,
                                     long ttlMillis) {
        requireUsable();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash = hash(secret);
        long now = clock.getAsLong();
        McpToken token = new McpToken(hash.substring(0, 8), hash, owner, role, scope,
                label == null ? "" : label, now, ttlMillis > 0 ? now + ttlMillis : 0);
        byHash.put(hash, token);
        save();
        return new Issued(token, secret);
    }

    /** 请求带来的明文认人;不认识、过期了都是空。 */
    public synchronized Optional<McpToken> authenticate(String secret) {
        if (secret == null || !secret.startsWith(PREFIX)) {
            return Optional.empty();
        }
        McpToken token = byHash.get(hash(secret));
        return token == null || token.expired(clock.getAsLong()) ? Optional.empty() : Optional.of(token);
    }

    /** 这位主人名下的令牌(含过期没清的,让他看得到并作废)。 */
    public synchronized List<McpToken> ownedBy(UUID owner) {
        return byHash.values().stream().filter(t -> t.owner().equals(owner)).toList();
    }

    /** 这张令牌现在还在册;会话据此在令牌作废、过期时断开。 */
    public synchronized boolean valid(McpToken token) {
        McpToken have = byHash.get(token.hash());
        return have != null && !have.expired(clock.getAsLong());
    }

    /** 作废主人名下编号为 {@code id} 的一张;没有这张是 false。别人的令牌碰不到。 */
    public synchronized boolean revoke(UUID owner, String id) {
        requireUsable();
        for (McpToken token : byHash.values()) {
            if (token.owner().equals(owner) && token.id().equals(id)) {
                byHash.remove(token.hash());
                save();
                return true;
            }
        }
        return false;
    }

    private void save() {
        JsonArray rows = new JsonArray();
        byHash.values().forEach(t -> rows.add(toJson(t)));
        JsonObject root = new JsonObject();
        root.add("tokens", rows);
        doc.write(root);
    }

    private static JsonObject toJson(McpToken t) {
        JsonObject o = new JsonObject();
        o.addProperty("hash", t.hash());
        o.addProperty("owner", t.owner().toString());
        o.addProperty("role", t.role().name());
        if (t.scope().all()) {
            o.addProperty("scope", "all");
        } else {
            JsonArray only = new JsonArray();
            t.scope().only().forEach(c -> only.add(c.toString()));
            o.add("scope", only);
        }
        o.addProperty("label", t.label());
        o.addProperty("created", t.createdAt());
        o.addProperty("expires", t.expiresAt());
        return o;
    }

    private static McpToken fromJson(JsonObject o) {
        String hash = o.get("hash").getAsString();
        McpToken.Scope scope;
        if (o.get("scope").isJsonArray()) {
            Set<UUID> only = new HashSet<>();
            o.getAsJsonArray("scope").forEach(c -> only.add(UUID.fromString(c.getAsString())));
            scope = McpToken.Scope.of(only);
        } else {
            scope = McpToken.Scope.ALL;
        }
        return new McpToken(hash.substring(0, 8), hash, UUID.fromString(o.get("owner").getAsString()),
                McpToken.Role.valueOf(o.get("role").getAsString()), scope, o.get("label").getAsString(),
                o.get("created").getAsLong(), o.get("expires").getAsLong());
    }

    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
