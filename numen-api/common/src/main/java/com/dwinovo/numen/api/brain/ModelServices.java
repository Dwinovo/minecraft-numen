package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.llm.ModelCatalog;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.config.ServerSettings;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.keys.Keychain;
import com.dwinovo.numen.api.keys.Keys;
import com.dwinovo.numen.api.keys.ServerKeyQuota;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.platform.Services;
import net.minecraft.server.MinecraftServer;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 这台服务器上跟"调模型"有关的几件:服务器的型号目录、服主的钥匙串、每位玩家的钥匙串、服主 key 的每人每天限额,
 * 以及把它们和库合起来的每位玩家的模型档案库({@link ProviderLibrary})。同一位玩家永远是同一份,关服时放手。
 *
 * <p>线程:服务端主线程。
 */
public final class ModelServices {

    private static ModelServices current;

    static {
        ServerLifecycle.onStopped(() -> {
            synchronized (ModelServices.class) {
                current = null;
            }
        });
    }

    private final MinecraftServer server;
    private final Homes homes;
    private final ModelCatalog catalog;
    private final Keychain serverKeys;
    private final ServerSettings settings;
    private final ServerKeyQuota quota;
    private final Map<UUID, Keychain> ownerKeys = new ConcurrentHashMap<>();

    private ModelServices(MinecraftServer server, Homes homes) {
        this.server = server;
        this.homes = homes;
        this.catalog = new ModelCatalog(homes.server().file("cache").resolve("models-dev.json"),
                () -> Services.config().getProxy());
        this.serverKeys = new Keychain(homes.server());
        this.settings = new ServerSettings(homes.server(), server.isDedicatedServer());
        this.quota = new ServerKeyQuota(settings, homes.server(), LocalDate::now);
    }

    /** 服务器起来时调一次。 */
    public static synchronized ModelServices start(MinecraftServer server) {
        current = new ModelServices(server, Homes.of(server));
        current.catalog.refresh();
        return current;
    }

    public static synchronized ModelServices of(MinecraftServer server) {
        if (current == null || current.server != server) {
            throw new IllegalStateException("模型服务还没开张——服务器起好时 ModelServices.start 该被调用");
        }
        return current;
    }

    public ModelCatalog catalog() {
        return catalog;
    }

    public ServerSettings settings() {
        return settings;
    }

    public ServerKeyQuota quota() {
        return quota;
    }

    /** 服主的钥匙串。 */
    public Keychain serverKeychain() {
        return serverKeys;
    }

    /** 这位玩家的钥匙串。 */
    public Keychain ownerKeychain(UUID owner) {
        return ownerKeys.computeIfAbsent(owner, id -> new Keychain(homes.owner(id)));
    }

    /** 这位玩家用 key 的顺序:先他自己的,再服主的。 */
    public Keys keys(UUID owner) {
        return new Keys(ownerKeychain(owner), serverKeys);
    }

    /** 这位玩家的模型档案库:服务器的内置加他的"我的"。 */
    public ProviderLibrary providers(UUID owner) {
        return new ProviderLibrary(LibraryHub.of(server).library(ProviderLibrary.KIND, owner), keys(owner), catalog);
    }

    /** {@code /reload}:管理员改了钥匙串和设置文件之后重新读。 */
    public void reload() {
        serverKeys.reload();
        ownerKeys.values().forEach(Keychain::reload);
        settings.reload();
    }
}
