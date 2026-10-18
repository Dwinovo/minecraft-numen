package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.program.ServerPrograms;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * 主人在不在——<b>只在这一处算</b>。她的身体(循环的 {@code HostPort.ownerPresent})、征询台({@code ConsentDesk})、
 * 远程端点都问 {@link #present},谁也不自己判。
 *
 * <p>主人在 = 他人在游戏里,或者他的外部大脑连着(远程端点上持他大脑令牌的会话),或者渠道上他的话正由服务端大脑
 * 想着({@link Visits},想完就不算)。都没有就是不在:
 * 内脑待命({@code Hold.AWAY}),她的程序收掉,收件箱照收。外部大脑连回来就算他回来了。
 *
 * <p>不在的那一刻要收拾一次({@link #collect}):作废在飞的回应、收掉程序。收拾的触发有两个——他的玩家下线
 * ({@link #playerLeft},没有外部大脑接着)与另外两样撑着他在场的东西没了({@link #settle}:外部大脑断开、来访做完,
 * 人也不在游戏里)。
 *
 * <p>线程:服务端主线程。
 */
public final class OwnerPresence {

    private OwnerPresence() {}

    /** 主人此刻在不在。 */
    public static boolean present(MinecraftServer server, UUID owner) {
        return server.getPlayerList().getPlayer(owner) != null || McpMode.instance().brainConnected(owner)
                || Visits.visiting(owner);
    }

    /**
     * 他的玩家下线了(登出事件)。客户端那一头的东西总要放掉;还有外部大脑连着就算他还在,不收拾,
     * 它断开时再收({@link #settle})。
     */
    public static void playerLeft(UUID owner) {
        CompanionLoops.clientLeft(owner);
        if (!McpMode.instance().brainConnected(owner) && !Visits.visiting(owner)) {
            collect(owner);
        }
    }

    /** 撑着他在场的外部大脑断开了、渠道来访做完了:人也不在游戏里、别的也没撑着,就和他登出一样收拾一次。 */
    public static void settle(MinecraftServer server, UUID owner) {
        if (!present(server, owner)) {
            collect(owner);
        }
    }

    private static void collect(UUID owner) {
        ServerPrograms.ownerLeft(owner);
        CompanionLoops.ownerGone(owner);
    }
}
