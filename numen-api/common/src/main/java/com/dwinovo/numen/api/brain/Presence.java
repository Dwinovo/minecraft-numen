package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.platform.ServerLifecycle;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 哪些在线的玩家装着我们的客户端:服务端开场({@code ServerHelloPayload})之后,客户端报到,报两样东西——协议版本({@code HelloPayload})与
 * 它提供哪些目标模组只在客户端的联动({@code ClientCapsPayload},见 {@link com.dwinovo.numen.api.plugin.ClientMods})。<b>两样都到齐才算报到</b>:
 * 这是"发不发 Numen 包"的唯一判据({@code NumenNetwork#sendToPlayer}),也是"这位主人的客户端提供哪些联动"的唯一出处
 * ({@link #provides}:API 清单、技能表、客户端函数的放行都读它)。报到过的才推 {@link Feed} 等一切;没有的,服务端大脑的回话改成只发给主人的
 * 系统消息。版本对不上的客户端不算数——它看不懂我们推的东西。
 *
 * <p>因为"有客户端"与"客户端提供什么"在同一刻成立,一个时刻里提示词里列着哪些客户端联动、调用它们放不放行,永远一致:客户端还没到齐的那些
 * 刻,既没有客户端函数可调(调用当场失败),提示词里也没有它们。
 *
 * <p>线程:任何线程。
 */
public final class Presence {

    /** 服务端与客户端之间推送与请求的协议版本。改了线上的样子就加一。 */
    public static final int PROTOCOL = 6;

    /** 报过协议版本、版本对得上、还在等它报能力的玩家。 */
    private static final Set<UUID> REPORTED = ConcurrentHashMap.newKeySet();
    /** 报到齐了的玩家,连同他的客户端提供的联动名字空间。 */
    private static final Map<UUID, Set<String>> CLIENTS = new ConcurrentHashMap<>();

    static {
        ServerLifecycle.onStopped(() -> {
            REPORTED.clear();
            CLIENTS.clear();
        });
    }

    private Presence() {}

    /** 客户端报了协议版本。返回它能不能用(版本对得上);对得上的接着等它报能力,对不上的此前报过的作废。 */
    public static boolean reported(UUID player, int protocol) {
        boolean usable = protocol == PROTOCOL;
        CLIENTS.remove(player);
        if (usable) {
            REPORTED.add(player);
        } else {
            REPORTED.remove(player);
        }
        return usable;
    }

    /**
     * 客户端报了它提供的联动。版本已经报过且对得上才算报到齐;返回有没有算数。
     *
     * @param provided 它提供的目标模组只在客户端的联动的名字空间
     */
    public static boolean ready(UUID player, Set<String> provided) {
        if (!REPORTED.remove(player)) {
            return false;
        }
        CLIENTS.put(player, Set.copyOf(provided));
        return true;
    }

    /** 这位玩家有可用的客户端(报到齐了)。 */
    public static boolean hasClient(UUID player) {
        return CLIENTS.containsKey(player);
    }

    /** 这位玩家的客户端提供这个联动名字空间。没有可用的客户端就是没有。 */
    public static boolean provides(UUID player, String namespace) {
        return CLIENTS.getOrDefault(player, Set.of()).contains(namespace);
    }

    /** 这位玩家下线了。 */
    public static void left(UUID player) {
        REPORTED.remove(player);
        CLIENTS.remove(player);
    }
}
