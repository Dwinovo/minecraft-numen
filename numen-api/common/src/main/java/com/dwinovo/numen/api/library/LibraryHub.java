package com.dwinovo.numen.api.library;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.OwnerPrefs;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.store.Lanes;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 这台服务器上所有库的出处:每种库的内置组只有一份(服务器目录),每位玩家每种库一份"我的"。各库的消费方
 * ({@code PersonaLibraries}、技能、模块、模型档案、MCP 工具)拿同一位玩家的同一份 {@link Library},读写的是同一个对象。
 * 服务器起来时先把 jar 里的内置同步进服务器目录({@link Shipped#sync}),再开张;关服时放手。
 *
 * <p>评测与单测没有服务器:{@link #headless} 在一个目录上开出同样的一份,同步、合并、开关都是同一套。
 *
 * <p>线程:任何线程取;写在服务端主线程。
 */
public final class LibraryHub {

    private static LibraryHub current;

    static {
        ServerLifecycle.onStopped(() -> {
            synchronized (LibraryHub.class) {
                current = null;
            }
        });
    }

    private final MinecraftServer server;
    private final Homes homes;
    private final Function<UUID, Switches> switches;
    private final Shipped.Manifest manifest;
    private final Map<String, Shelf> builtin = new ConcurrentHashMap<>();
    private final Map<String, Library> libraries = new ConcurrentHashMap<>();

    private LibraryHub(MinecraftServer server, Homes homes, Function<UUID, Switches> switches,
                       Shipped.Manifest manifest) {
        this.server = server;
        this.homes = homes;
        this.switches = switches;
        this.manifest = manifest;
    }

    /** 服务器起来时调一次:把各 jar 的内置同步进服务器目录,然后开张。 */
    public static synchronized LibraryHub start(MinecraftServer server) {
        Homes homes = Homes.of(server);
        current = new LibraryHub(server, homes, owner -> OwnerPrefs.of(server, owner),
                Shipped.sync(homes.server().dir()));
        return current;
    }

    /**
     * 没有服务器的地方(评测、单测)在 {@code root} 上开一份:{@code root/server/} 是服务器目录,{@code root/players/<uuid>/}
     * 是玩家目录,写盘在调用线程上直接做,开关记在内存里。
     */
    public static LibraryHub headless(Path root) {
        return headless(root, Shipped.bundles());
    }

    /** 同上,内置的来源由调用方给(单测造一个"jar")。再调一次相当于服务器重启:同步按清单重新做一遍。 */
    public static LibraryHub headless(Path root, List<Shipped.Bundle> bundles) {
        Homes homes = new Homes(root.resolve("world"), root, new Lanes(Runnable::run,
                e -> Constants.LOG.warn("[numen-library] {}", e.getMessage())));
        Map<UUID, Switches> memory = new ConcurrentHashMap<>();
        return new LibraryHub(null, homes, owner -> memory.computeIfAbsent(owner, id -> Switches.inMemory()),
                Shipped.sync(homes.server().dir(), bundles));
    }

    /** 这台服务器上开了张的那一份。{@link #start} 之前调用是编程错误。 */
    public static synchronized LibraryHub of(MinecraftServer server) {
        if (current == null || current.server != server) {
            throw new IllegalStateException("库还没开张——服务器起好时 LibraryHub.start 该被调用");
        }
        return current;
    }

    /** 这位玩家的这种库:服务器的内置加他的"我的"。 */
    public Library library(LibraryKind kind, UUID owner) {
        return libraries.computeIfAbsent(kind.dir() + "/" + owner, key -> new Library(kind, builtin(kind),
                new Shelf(kind, homes.owner(owner)), switches.apply(owner), manifest));
    }

    /** 服务器目录里 jar 同步的清单。 */
    public Shipped.Manifest manifest() {
        return manifest;
    }

    /** 这种库的内置组(所有玩家共用,管理员写它)。 */
    public Shelf builtin(LibraryKind kind) {
        return builtin.computeIfAbsent(kind.dir(), dir -> new Shelf(kind, homes.server()));
    }

    /** 把已经开过的库都重扫一遍({@code /reload}):手改磁盘上的文件在这里读到。 */
    public void reloadAll() {
        builtin.values().forEach(Shelf::reload);
        libraries.values().forEach(library -> library.mine().reload());
    }
}
