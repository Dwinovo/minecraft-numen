package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.program.ServerPrograms;
import com.dwinovo.numen.api.seat.ServerSeats;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 她的主人在不在——<b>只在这一处算</b>。她的身体(循环的 {@code HostPort.ownerPresent})、征询台({@code ConsentDesk})、
 * 出勤的对账({@link Companions#reconcile})都问 {@link #present},谁也不自己判。
 *
 * <p>她有一张主人表(创建者加分享给的人,见 {@code docs/adr/runtime/0006-keepers-ownership-use-provenance.md}),
 * <b>表上任一位在场就算在</b>。一位主人在场 = 他人在游戏里,或者渠道上他的话正由服务端大脑想着({@link Visits},想完就不算);
 * 创建者另有一条:他的外部大脑连着(远程端点上持他大脑令牌的会话)。被分享的人的外部大脑驾驶不了她,撑不起"此刻有人能驾驶她",不算。
 * 都没有就是不在:内脑待命({@code Hold.AWAY}),她的程序收掉,收件箱照收,她的身体登出世界;有一位回来她也跟着回到世界里。
 * 身体的去留不在这里定:在不在场变了的每个时机都只是让 {@link Companions#reconcile} 对一次账(判据见
 * {@link com.dwinovo.numen.api.entity.Attendance})。
 *
 * <p>不在的那一刻要收拾一次({@link #collect}):作废在飞的回应、收掉程序。收拾按同伴算——一位主人离开了,他用得了的各只里
 * 表上已无人在场的才收拾。触发有两个:他的玩家下线({@link #playerLeft})与另外两样撑着他在场的东西没了
 * ({@link #settle}:外部大脑断开、来访做完)。
 *
 * <p>线程:服务端主线程。
 */
public final class OwnerPresence {

    private OwnerPresence() {}

    /**
     * 她的主人此刻在不在场:表上任一位在场。
     *
     * @param keepers 她的主人表,创建者在前
     */
    public static boolean present(MinecraftServer server, List<UUID> keepers) {
        return anyPresent(keepers, inGame(server), McpMode.instance()::brainConnected, Visits::visiting);
    }

    /**
     * 判据本身,不碰 Minecraft:表上有一位人在游戏里或正被渠道来访着,或者创建者(表的第一位)的外部大脑连着。
     */
    static boolean anyPresent(List<UUID> keepers, Predicate<UUID> inGame, Predicate<UUID> brainConnected,
                              Predicate<UUID> visiting) {
        for (int i = 0; i < keepers.size(); i++) {
            UUID keeper = keepers.get(i);
            if (inGame.test(keeper) || visiting.test(keeper) || i == 0 && brainConnected.test(keeper)) {
                return true;
            }
        }
        return false;
    }

    private static Predicate<UUID> inGame(MinecraftServer server) {
        return who -> server.getPlayerList().getPlayer(who) != null;
    }

    /**
     * 这位主人的玩家下线了(登出事件,这一刻他还在玩家表里,所以他不算数)。客户端那一头的东西总要放掉;他用得了的各只里,
     * 别的主人也不在场的收拾一次。
     */
    public static void playerLeft(MinecraftServer server, UUID person) {
        CompanionLoops.clientLeft(person);
        ServerSeats.left(person);
        NumenNetwork.disconnected(person);
        Predicate<UUID> others = inGame(server).and(who -> !who.equals(person));
        collectAbsent(server, person, others);
    }

    /**
     * 撑着这位主人在场的外部大脑断开了、渠道来访做完了:他用得了的各只里表上已无人在场的,和主人登出一样收拾一次,
     * 她们的身体也跟着对账。
     */
    public static void settle(MinecraftServer server, UUID person) {
        collectAbsent(server, person, inGame(server));
        Companions.reconcile(server, person);
    }

    /**
     * 她的主人表变了(少了一位):表上已无人在场就收拾一次。身体的对账由改表的那一处接着做。
     */
    public static void keepersChanged(MinecraftServer server, UUID companion) {
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companion);
        if (entry != null && !present(server, entry.keepers())) {
            collect(companion);
        }
    }

    private static void collectAbsent(MinecraftServer server, UUID person, Predicate<UUID> inGame) {
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).usableBy(person)) {
            if (!anyPresent(e.getValue().keepers(), inGame, McpMode.instance()::brainConnected, Visits::visiting)) {
                collect(e.getKey());
            }
        }
    }

    private static void collect(UUID companion) {
        ServerPrograms.keepersGone(companion);
        CompanionLoops.keepersGone(companion);
    }
}
