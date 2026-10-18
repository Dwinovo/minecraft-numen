package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.skin.MineSkinClient;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.skin.SkinSigning;
import net.minecraft.server.MinecraftServer;

/**
 * 这台服务器的皮肤签名队({@link SkinSigning}):服务器起来时开,停时关。MineSkin 的 API key 每次发之前从服主的钥匙串里现取
 * ({@code mineskin} 这个服务),改了立即生效;队里的变化推给当事人的客户端(皮肤库的表里带着排队的几行)。
 *
 * <p>线程:服务端主线程。
 */
public final class SkinSignings {

    private static SkinSigning current;
    private static MinecraftServer currentServer;

    static {
        ServerLifecycle.onStopped(() -> {
            synchronized (SkinSignings.class) {
                if (current != null) {
                    current.close();
                }
                current = null;
                currentServer = null;
            }
        });
    }

    private SkinSignings() {}

    /** 服务器起来时调一次。 */
    public static synchronized void start(MinecraftServer server) {
        if (current != null) {
            current.close();
        }
        ModelServices models = ModelServices.of(server);
        current = new SkinSigning(SkinSigning.Backend.NETWORK,
                () -> models.serverKeychain().get(MineSkinClient.KEY_SERVICE), server,
                owner -> Replies.pushTo(server, owner, Libraries.SKIN), (request, outcome) -> {
                    boolean builtin = request.shelf() == LibraryHub.of(server).builtin(SkinProfile.KIND);
                    CompanionBinding.skinSigned(server, request.owner(), builtin, outcome);
                    if (builtin) {
                        Replies.share(server, null, Libraries.SKIN);   // 内置的皮肤变了,别的玩家也要看到
                    }
                }, Thread::sleep);
        currentServer = server;
    }

    public static synchronized SkinSigning of(MinecraftServer server) {
        if (current == null || currentServer != server) {
            throw new IllegalStateException("皮肤签名队还没开张——服务器起好时 SkinSignings.start 该被调用");
        }
        return current;
    }
}
