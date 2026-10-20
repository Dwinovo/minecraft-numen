package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.MojangSkins;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.skin.MineSkinClient;
import com.dwinovo.numen.api.skin.SkinCache;
import com.dwinovo.numen.api.skin.SkinFolder;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.skin.SkinRef;
import com.dwinovo.numen.api.skin.SkinSigning;
import com.dwinovo.numen.api.skin.Wardrobe;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * 这台服务器上皮肤这一块的接线:签名缓存({@link SkinCache},服务器目录下)、签名队({@link SkinSigning})、给同伴换皮肤的流程
 * ({@link Wardrobe}),服务器起来时开,停时关。MineSkin 的 API key 每次发之前从服主的钥匙串里现取({@code mineskin} 这个服务),
 * 改了立即生效;队里的变化推给当事人的客户端(皮肤这张表里带着排队的几行)。穿上就是 {@link Companions#applySkin} 换身体,
 * 再把穿的是缓存里的哪一张记进她的绑定({@link CompanionLoop#wearSkin})。
 *
 * <p>线程:服务端主线程。
 */
public final class ServerSkins {

    private static ServerSkins current;
    private static MinecraftServer currentServer;

    static {
        ServerLifecycle.onStopped(() -> {
            synchronized (ServerSkins.class) {
                if (current != null) {
                    current.signer.close();
                }
                current = null;
                currentServer = null;
            }
        });
    }

    private final SkinSigning signer;
    private final SkinCache cache;
    private final Wardrobe wardrobe;

    private ServerSkins(SkinSigning signer, SkinCache cache, Wardrobe wardrobe) {
        this.signer = signer;
        this.cache = cache;
        this.wardrobe = wardrobe;
    }

    /** 服务器起来时调一次:签名走网上的 MineSkin 与 Mojang。 */
    public static void start(MinecraftServer server, SkinCache cache) {
        start(server, SkinSigning.Backend.NETWORK, cache);
    }

    /** 同上,网上的四件事由调用方给(GameTest 换成不联网的)。再调一次换掉上一份。 */
    public static void start(MinecraftServer server, SkinSigning.Backend backend) {
        start(server, backend, new SkinCache(Homes.of(server).server()));
    }

    /** 同上,缓存由调用方给:启动时的迁移往里放过东西,用的是同一份。 */
    public static synchronized void start(MinecraftServer server, SkinSigning.Backend backend, SkinCache cache) {
        if (current != null) {
            current.signer.close();
        }
        ModelServices models = ModelServices.of(server);
        SkinSigning signer = new SkinSigning(backend, () -> models.serverKeychain().get(MineSkinClient.KEY_SERVICE), server,
                owner -> Replies.pushTo(server, owner, Libraries.SKIN), cache, Thread::sleep);
        current = new ServerSkins(signer, cache, new Wardrobe(signer, cache, SkinFolder.here(), dress(server)));
        currentServer = server;
    }

    public static synchronized ServerSkins of(MinecraftServer server) {
        if (current == null || currentServer != server) {
            throw new IllegalStateException("皮肤还没开张——服务器起好时 ServerSkins.start 该被调用");
        }
        return current;
    }

    public SkinSigning signer() {
        return signer;
    }

    public SkinCache cache() {
        return cache;
    }

    public Wardrobe wardrobe() {
        return wardrobe;
    }

    private static Wardrobe.Dress dress(MinecraftServer server) {
        return new Wardrobe.Dress() {
            @Override
            public boolean wear(UUID companion, SkinRef ref, SkinProfile skin) {
                if (CompanionRegistry.get(server).find(companion) == null) {
                    return false;
                }
                Companions.applySkin(server, companion, new MojangSkins.Skin(skin.value(), skin.signature()));
                CompanionLoops.get(companion).ifPresent(loop -> loop.wearSkin(ref.toString()));
                return true;
            }

            @Override
            public void takeOff(UUID companion) {
                Companions.applySkin(server, companion, null);
                CompanionLoops.get(companion).ifPresent(loop -> loop.wearSkin(null));
            }
        };
    }
}
