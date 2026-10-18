package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.brain.OwnerPresence;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 玩家登入、登出、换维度时 Numen API 要做的事——每个平台把自己的事件接到这三个入口,不各抄一份。
 * 同伴自己也是 {@link ServerPlayer},她登入登出走的是别的路(见 {@code Companions}),这里一律跳过。
 *
 * <p>线程:服务端主线程。
 */
public final class PlayerSessions {

    private PlayerSessions() {}

    /** 一位玩家登入了:他名下沉睡的同伴排队等着回来,同伴名册发给他。 */
    public static void joined(ServerPlayer player) {
        if (player instanceof NumenPlayer) {
            return;
        }
        // 只排队,不在这儿恢复:登入事件跑在原版 placeNewPlayer 内部,在这里出的任何异常都会打断主人的入场,
        // 客户端只看到"无效的玩家数据"。
        Companions.scheduleRestoreFor(player.getUUID());
        Companions.syncRosterToOwner(player.server, player);
    }

    /** 主人断线:他名下在服务端跑着的程序收掉,等他答复的反向请求失败,他的模块缓存清掉。 */
    public static void left(ServerPlayer player) {
        if (!(player instanceof NumenPlayer)) {
            OwnerPresence.playerLeft(player.getUUID());
        }
    }

    /** 同伴自己穿过了传送门:告诉她的大脑(环境事件)。 */
    public static void changedDimension(ServerPlayer player) {
        if (player instanceof NumenPlayer companion) {
            Companions.onDimensionChanged(companion);
        }
    }
}
