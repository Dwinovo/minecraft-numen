package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.platform.ServerLifecycle;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 哪些在线的玩家装着我们的客户端:服务端开场({@code ServerHelloPayload})之后,客户端报到({@code HelloPayload}),带着它说的协议版本。
 * 这是"发不发 Numen 包"的唯一判据({@code NumenNetwork#sendToPlayer}):报到过的才推 {@link Feed} 等一切;
 * 没有的,服务端大脑的回话改成只发给主人的系统消息。版本对不上的客户端不算数——它看不懂我们推的东西。
 *
 * <p>线程:任何线程。
 */
public final class Presence {

    /** 服务端与客户端之间推送与请求的协议版本。改了线上的样子就加一。 */
    public static final int PROTOCOL = 5;

    private static final Map<UUID, Boolean> CLIENTS = new ConcurrentHashMap<>();

    static {
        ServerLifecycle.onStopped(CLIENTS::clear);
    }

    private Presence() {}

    /** 客户端报到。返回它能不能用(版本对得上)。 */
    public static boolean hello(UUID player, int protocol) {
        boolean usable = protocol == PROTOCOL;
        if (usable) {
            CLIENTS.put(player, Boolean.TRUE);
        } else {
            CLIENTS.remove(player);
        }
        return usable;
    }

    /** 这位玩家有可用的客户端。 */
    public static boolean hasClient(UUID player) {
        return CLIENTS.containsKey(player);
    }

    /** 这位玩家下线了。 */
    public static void left(UUID player) {
        CLIENTS.remove(player);
    }
}
