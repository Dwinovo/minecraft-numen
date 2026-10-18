package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.brain.OwnerPresence;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.network.payload.CompanionListPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家登入、登出、换维度时 Numen API 要做的事——每个平台把自己的事件接到这三个入口,不各抄一份。
 * 同伴自己也是 {@link ServerPlayer},她登入登出走的是别的路(见 {@code Companions}),这里一律跳过。
 *
 * <p>线程:服务端主线程。
 */
public final class PlayerSessions {

    /** 已经推过名册的玩家。 */
    private static final Set<UUID> ANNOUNCED = ConcurrentHashMap.newKeySet();

    static {
        ServerLifecycle.onStopped(ANNOUNCED::clear);
    }

    private PlayerSessions() {}

    /** 一位玩家登入了:他名下沉睡的同伴排队等着回来,同伴名册发给他。 */
    public static void joined(ServerPlayer player) {
        if (player instanceof NumenPlayer) {
            return;
        }
        // 只排队,不在这儿恢复:登入事件跑在原版 placeNewPlayer 内部,在这里出的任何异常都会打断主人的入场,
        // 客户端只看到"无效的玩家数据"。
        Companions.scheduleRestoreFor(player.getUUID());
        // 模组上登入时他的连接已经能收我们的包,名册就在这里推出去;Paper 上 Fabric 客户端要进游戏之后才登记频道,
        // 这一刻推不出去,由频道登记的事件再调一次 channelReady
        channelReady(player);
    }

    /**
     * 这位玩家在世界里、他的连接也能收我们的包了:把同伴名册推给他。客户端收到名册才向服务端报到(Hello),
     * 所以这一步不做,他永远不会报到。两个条件谁后成立,谁来调——登入和频道登记各调一次;一次会话里只推一次(重复调用无害)。
     */
    public static void channelReady(ServerPlayer player) {
        if (player instanceof NumenPlayer || !player.server.getPlayerList().getPlayers().contains(player)
                || !Services.network().canSend(player, CompanionListPayload.TYPE)
                || !ANNOUNCED.add(player.getUUID())) {
            return;
        }
        Companions.syncRosterToOwner(player.server, player);
    }

    /** 主人断线:他名下在服务端跑着的程序收掉,等他答复的反向请求失败,他的模块缓存清掉。 */
    public static void left(ServerPlayer player) {
        if (!(player instanceof NumenPlayer)) {
            OwnerPresence.playerLeft(player.getUUID());
            ANNOUNCED.remove(player.getUUID());
        }
    }

    /** 同伴自己穿过了传送门:告诉她的大脑(环境事件)。 */
    public static void changedDimension(ServerPlayer player) {
        if (player instanceof NumenPlayer companion) {
            Companions.onDimensionChanged(companion);
        }
    }
}
