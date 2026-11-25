package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.program.ServerPrograms;
import com.dwinovo.numen.api.seat.ServerSeats;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;

/**
 * 她的主人在不在——<b>只在这一处算</b>。她的身体(登出与回来)、征询台({@code ConsentDesk})、出勤的对账({@code Companions#reconcile})
 * 都问 {@link #present},谁也不自己判。
 *
 * <p>她的脑子只在她的创建者的客户端上,所以<b>在场就是创建者的座位上有人坐</b>({@link ServerSeats#occupied}):创建者的客户端连着、
 * 坐上了她的座位——内置大脑与外接大脑都经这个座位。共享主人在线、创建者不在,没有谁能驾驶她,她照 body 0008 登出。
 * 身体的去留不在这里定:座位有人坐、没人坐的每个时机都只是让 {@code Companions#reconcile} 对一次账(判据见 {@code Attendance})。
 *
 * <p>不在的那一刻要收拾一次({@link #collect}):她的程序收掉。触发是座位上没人了:创建者的玩家下线({@link #playerLeft})。
 *
 * <p>线程:服务端主线程。
 */
public final class OwnerPresence {

    private OwnerPresence() {}

    /** 她的主人此刻在不在场:她的座位上有人坐。 */
    public static boolean present(UUID companion) {
        return ServerSeats.occupied(companion);
    }

    /**
     * 这位主人的玩家下线了。客户端那一头的东西总要放掉(座位上没人了,经座位起的程序当场切断);他用得了的各只的程序收拾一次。
     */
    public static void playerLeft(MinecraftServer server, UUID person) {
        ServerSeats.left(person);
        NumenNetwork.disconnected(person);
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).usableBy(person)) {
            collect(e.getKey());
        }
    }

    private static void collect(UUID companion) {
        ServerPrograms.keepersGone(companion);
    }
}
