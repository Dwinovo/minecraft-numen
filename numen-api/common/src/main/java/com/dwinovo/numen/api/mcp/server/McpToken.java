package com.dwinovo.numen.api.mcp.server;

import java.util.Set;
import java.util.UUID;

/**
 * 远程端点上的一张令牌:认的是一位主人,能指挥他名下的哪几只({@link Scope}),以什么身份指挥({@link Role})。
 * 明文只在发出的那一刻交给主人,这里存的是哈希;{@link #id} 取自哈希,列出、作废、审计都用它说"哪一张"。
 *
 * @param expiresAt 到这一刻(毫秒)作废;0 = 不过期
 */
public record McpToken(String id, String hash, UUID owner, Role role, Scope scope, String label,
                       long createdAt, long expiresAt) {

    /** 令牌的身份。 */
    public enum Role {
        /** 大脑:取件、发程序、说话——等于驾驶,会让她的驾驶席换人。 */
        BRAIN,
        /** 渠道:只能把主人的话送进收件箱,收她的回话与征询;不驾驶。 */
        CHANNEL
    }

    /** 能指挥主人名下的哪几只:全部(包括以后召唤的),或点名的几只。 */
    public record Scope(boolean all, Set<UUID> only) {

        public static final Scope ALL = new Scope(true, Set.of());

        public Scope {
            only = Set.copyOf(only);
        }

        public static Scope of(Set<UUID> companions) {
            return new Scope(false, companions);
        }

        public boolean covers(UUID companion) {
            return all || only.contains(companion);
        }
    }

    public boolean expired(long now) {
        return expiresAt != 0 && now >= expiresAt;
    }
}
