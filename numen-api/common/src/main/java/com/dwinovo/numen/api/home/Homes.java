package com.dwinovo.numen.api.home;

import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.NumenPaths;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.store.Lanes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 按归属组装的落盘位置——这个问题的<b>唯一</b>答案(docs/input-pipeline.md §六"存储的三层"第三层)。每样东西只有一个归属,
 * 归属决定它在哪个根目录。五处:
 * <ul>
 *   <li>{@link #companion}:她的东西(对话日志、收件箱、札记、目标、绑定、台账),在<b>世界存档</b>里
 *       {@code <存档>/numen/companions/<uuid>/}——备份世界就连她一起备份,删世界她也跟着走;</li>
 *   <li>{@link #worldOwner}:主人在这个世界里的东西(会话成员表),{@code <存档>/numen/players/<uuid>/};</li>
 *   <li>{@link #server}:服主管的东西(内置的各库、服主的钥匙串、服务器配置、型号目录缓存),{@code config/numen/server/};</li>
 *   <li>{@link #owner}:玩家自己的东西(我的各库、他的钥匙串、偏好),{@code config/numen/players/<uuid>/},
 *       跟着服务器走、换世界还能用;</li>
 *   <li>{@link #clientDir}:这台电脑的东西(界面偏好、声线、皮肤、语音服务的 key),{@code config/numen/client/}。</li>
 * </ul>
 * 单人游戏里服务端与客户端共用一个 {@code config/numen/},所以三个归属各占一个子目录,根下不放散文件。
 * 每处一条自己的写车道(见 {@link Lanes}):她的所有文件共用她那一条,所以她的日志、收件箱、札记按提交顺序落盘。
 *
 * <p>线程:任何线程。
 */
public final class Homes {

    /** 世界存档里 Numen 的目录名。 */
    private static final String WORLD_DIR = "numen";
    private static final String COMPANIONS = "companions";
    private static final String PLAYERS = "players";
    private static final String SERVER = "server";
    private static final String CLIENT = "client";

    private final Path world;
    private final Path config;
    private final Lanes lanes;

    /**
     * @param world  世界存档里 Numen 的目录
     * @param config 服务器上 Numen 的配置目录
     */
    public Homes(Path world, Path config, Lanes lanes) {
        this.world = world;
        this.config = config;
        this.lanes = lanes;
    }

    /** 她的家。 */
    public Home companion(UUID companion) {
        return home(world.resolve(COMPANIONS).resolve(companion.toString()), "companion/" + companion);
    }

    /** 主人在这个世界里的东西。 */
    public Home worldOwner(UUID owner) {
        return home(world.resolve(PLAYERS).resolve(owner.toString()), "world-owner/" + owner);
    }

    /** 玩家自己的库、钥匙串与偏好,在服务器上。 */
    public Home owner(UUID owner) {
        return home(config.resolve(PLAYERS).resolve(owner.toString()), "owner/" + owner);
    }

    /** 服主管的东西。 */
    public Home server() {
        return home(config.resolve(SERVER), "server");
    }

    /** 这台电脑自己的东西:客户端进程读写它,不经车道。 */
    public static Path clientDir() {
        return NumenPaths.config().resolve(CLIENT);
    }

    /** 同伴被遣散:她的车道不用留(她的目录由调用方删)。 */
    public void forgetCompanion(UUID companion) {
        lanes.forget("companion/" + companion);
    }

    private Home home(Path dir, String lane) {
        return new Home(dir, lanes.io(lane));
    }

    // ---- 这个进程里的那一份 ----

    private static Homes current;
    private static MinecraftServer currentServer;

    static {
        ServerLifecycle.onStopped(() -> {
            synchronized (Homes.class) {
                current = null;
                currentServer = null;
            }
        });
    }

    /** 这台服务器的位置:世界目录取自服务器,服务器配置取自 {@link NumenPaths},车道是 {@link NumenIo#lanes()}。 */
    public static synchronized Homes of(MinecraftServer server) {
        if (current == null || currentServer != server) {
            current = new Homes(server.getWorldPath(LevelResource.ROOT).resolve(WORLD_DIR), NumenPaths.config(),
                    NumenIo.lanes());
            currentServer = server;
        }
        return current;
    }
}
