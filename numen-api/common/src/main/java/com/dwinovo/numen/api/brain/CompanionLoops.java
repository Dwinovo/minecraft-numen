package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 这个世界里跑着的服务端大脑,按同伴的 UUID。谁在服务端要找某只同伴的大脑(事件送进她的收件箱、死亡与复活、主人登出)
 * 都问这一处;没有登记的同伴没有服务端大脑,调用方照旧走原来的路。
 *
 * <p>线程:服务端主线程。关服时全部收掉(世界作用域的状态,见 {@link ServerLifecycle})。
 */
public final class CompanionLoops {

    private record Entry(UUID owner, CompanionLoop loop) {}

    private static final Map<UUID, Entry> LOOPS = new ConcurrentHashMap<>();

    static {
        ServerLifecycle.onStopped(CompanionLoops::dropAll);
    }

    private CompanionLoops() {}

    /** 这只同伴的大脑;没有是空。 */
    public static Optional<CompanionLoop> get(UUID companion) {
        Entry entry = LOOPS.get(companion);
        return entry == null ? Optional.empty() : Optional.of(entry.loop());
    }

    /** 登记一只同伴的大脑;她原来的先收掉(它在飞的回合作废,不再写她的日志)。 */
    public static void install(UUID owner, CompanionLoop loop) {
        Entry old = LOOPS.put(loop.companion(), new Entry(owner, loop));
        if (old != null) {
            old.loop().dispose();
        }
    }

    /** 每个服务器刻一次:各大脑播报停牌变化、问熟度。 */
    public static void tick() {
        for (Entry entry : LOOPS.values()) {
            entry.loop().tick();
        }
    }

    /** 这位主人下线了:他名下各大脑作废在飞的回应、放弃未决调用,待命。 */
    public static void ownerLeft(UUID owner) {
        for (Entry entry : LOOPS.values()) {
            if (entry.owner().equals(owner)) {
                entry.loop().ownerLeft();
            }
        }
    }

    /**
     * 这只同伴不存在了(遣散):大脑收掉,她的家整个删——删的活排在她那条车道的最后,在它前面还没写下去的写先落完,
     * 之后不会有东西把已删的文件写回来。
     */
    public static void dismissed(MinecraftServer server, UUID companion) {
        Entry entry = LOOPS.remove(companion);
        if (entry != null) {
            entry.loop().dispose();
        }
        Homes homes = Homes.of(server);
        Home home = homes.companion(companion);
        home.delete();
        homes.forgetCompanion(companion);
    }

    private static void dropAll() {
        LOOPS.values().forEach(entry -> entry.loop().dispose());
        LOOPS.clear();
    }
}
