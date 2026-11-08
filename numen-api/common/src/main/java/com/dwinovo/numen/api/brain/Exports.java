package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.payload.ExportChunkPayload;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * 导出的 zip 下行:按段({@link ExportChunkPayload#SEGMENT_BYTES})依次推,<b>每位玩家每个服务器刻至多一段</b>——这就是下行的预算
 * (64 KiB × 20 刻 ≈ 1.25 MB/s),一只同伴聊久了日志几十 MB 也不会一下子挤满那位玩家的连接。每位玩家同一时刻一份在路上;
 * 玩家下线了就作废。总大小不设上限。拼回由客户端按序做({@link Segments.Assembler})。
 *
 * <p>线程:服务端主线程。
 */
public final class Exports {

    /** 正在往一位玩家推的一份。 */
    private static final class Transfer {
        final UUID companion;
        final String name;
        final Segments.Cut cut;

        Transfer(UUID companion, String name, byte[] zip) {
            this.companion = companion;
            this.name = name;
            this.cut = new Segments.Cut(zip);
        }

        ExportChunkPayload take() {
            int index = cut.next();
            return new ExportChunkPayload(companion, name, index, cut.total(), cut.take());
        }

        boolean done() {
            return cut.done();
        }
    }

    /** 服务端这一份。 */
    static final Exports SERVER = new Exports();

    static {
        ServerLifecycle.onStopped(() -> SERVER.transfers.clear());
    }

    private final Map<UUID, Transfer> transfers = new ConcurrentHashMap<>();

    /**
     * 往这位玩家推一份。他前一份还没推完就不收。
     *
     * @return 排上了;他还有一份在路上是 {@code false}
     */
    public boolean start(UUID player, UUID companion, String name, byte[] zip) {
        return transfers.putIfAbsent(player, new Transfer(companion, name, zip)) == null;
    }

    /** 每个服务器刻一次:在线的玩家各推出下一段,推完的收掉,下线的作废。 */
    public void tick(Predicate<UUID> online, BiConsumer<UUID, ExportChunkPayload> send) {
        Iterator<Map.Entry<UUID, Transfer>> each = transfers.entrySet().iterator();
        while (each.hasNext()) {
            Map.Entry<UUID, Transfer> entry = each.next();
            if (!online.test(entry.getKey())) {
                each.remove();
                continue;
            }
            send.accept(entry.getKey(), entry.getValue().take());
            if (entry.getValue().done()) {
                each.remove();
            }
        }
    }

    /** 服务端的每刻:推给真玩家。 */
    public static void tick(MinecraftServer server) {
        SERVER.tick(id -> server.getPlayerList().getPlayer(id) != null, (id, chunk) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            NumenNetwork.sendToPlayer(player, chunk);
        });
    }
}
