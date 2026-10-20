package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.payload.SkinHandoverPayload;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.store.Trees;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 连专用服务器的玩家,旧版本里存在服务器上的"我的皮肤"原图,交还到他本机的皮肤文件夹。皮肤现在是玩家自己机器上的 png,服务端不再替他存;
 * 他下一次带着客户端连上({@link Replies#hello})时,他旧皮肤库({@code players/<他>/skin/})里的每张原图按段下行
 * ({@link Segments},每位玩家每个服务器刻至多一段,同导出的记忆),一张发完那条旧记录就删掉——删掉就是"做完了,只做一次":没发完的
 * (断线)留着,下次他连上接着发。签好的数据早在启动时的迁移里进了缓存({@link SkinMigration}),这里只管原图。
 *
 * <p>线程:服务端主线程。
 */
public final class SkinHandover {

    /** 排着要发的一张:旧记录文件、皮肤名、切好的段。 */
    private record Pending(Path file, Path root, String name, Segments.Cut cut) {}

    /** 服务端这一份。 */
    static final SkinHandover SERVER = new SkinHandover();

    static {
        ServerLifecycle.onStopped(() -> SERVER.queues.clear());
    }

    private final Map<UUID, Deque<Pending>> queues = new ConcurrentHashMap<>();

    /**
     * 他旧皮肤库里还没交的原图排上队。已经排着的不重复排;没有原图的旧记录(当年借来的正版皮肤)没什么可交,直接删掉。
     *
     * @param skinRoot 他的旧皮肤库目录({@code players/<他>/skin})
     * @return 排上了几张
     */
    public int offer(UUID player, Path skinRoot) {
        if (!Files.isDirectory(skinRoot)) {
            return 0;
        }
        Deque<Pending> queue = queues.computeIfAbsent(player, id -> new ArrayDeque<>());
        Set<Path> queued = new HashSet<>();
        queue.forEach(pending -> queued.add(pending.file()));
        int added = 0;
        for (Path file : records(skinRoot)) {
            if (queued.contains(file)) {
                continue;
            }
            String leaf = file.getFileName().toString();
            String name = leaf.substring(0, leaf.length() - ".json".length());
            byte[] png;
            try {
                png = SkinProfile.read(Files.readString(file, StandardCharsets.UTF_8)).png();
            } catch (IOException | RuntimeException unreadable) {
                Constants.LOG.warn("[numen-migrate] 旧皮肤 {} 读不了,留着不动: {}", file, unreadable.toString());
                continue;
            }
            if (png.length == 0) {
                discard(file, skinRoot);
                continue;
            }
            queue.add(new Pending(file, skinRoot, name, new Segments.Cut(png)));
            added++;
        }
        return added;
    }

    /** 每个服务器刻一次:在线的玩家各发出下一段,一张发完删掉它的旧记录,下线的不再发(留着,下次接着发)。 */
    public void tick(Predicate<UUID> online, BiConsumer<UUID, SkinHandoverPayload> send) {
        Iterator<Map.Entry<UUID, Deque<Pending>>> each = queues.entrySet().iterator();
        while (each.hasNext()) {
            Map.Entry<UUID, Deque<Pending>> entry = each.next();
            if (!online.test(entry.getKey())) {
                each.remove();
                continue;
            }
            Pending head = entry.getValue().peek();
            if (head == null) {
                each.remove();
                continue;
            }
            int index = head.cut().next();
            send.accept(entry.getKey(), new SkinHandoverPayload(head.name(), index, head.cut().total(), head.cut().take()));
            if (head.cut().done()) {
                entry.getValue().poll();
                discard(head.file(), head.root());
            }
        }
    }

    /** 服务端的每刻:推给真玩家。 */
    public static void tick(MinecraftServer server) {
        SERVER.tick(id -> server.getPlayerList().getPlayer(id) != null, (id, segment) ->
                NumenNetwork.sendToPlayer(server.getPlayerList().getPlayer(id), segment));
    }

    /** 他的客户端报到了:旧皮肤库里没交的原图排上队。 */
    static void offer(MinecraftServer server, ServerPlayer player) {
        SERVER.offer(player.getUUID(), Homes.of(server).owner(player.getUUID()).file("skin"));
    }

    /** 发完的一条旧记录删掉,空了的命名空间目录和库目录一起收掉。 */
    private static void discard(Path file, Path root) {
        Trees.deleteFile(file);
        Trees.deleteEmptyParents(file.getParent(), root.getParent());
    }

    private static List<Path> records(Path root) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> spaces = Files.list(root)) {
            for (Path space : spaces.filter(Files::isDirectory).sorted().toList()) {
                try (Stream<Path> files = Files.list(space)) {
                    files.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".json")).sorted()
                            .forEach(out::add);
                }
            }
        } catch (IOException e) {
            Constants.LOG.warn("[numen-migrate] 读不了旧皮肤库 {}: {}", root, e.toString());
        }
        return out;
    }
}
