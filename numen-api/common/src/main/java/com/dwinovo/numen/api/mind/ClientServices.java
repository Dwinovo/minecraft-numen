package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.agent.llm.ModelCatalog;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.brain.Bound;
import com.dwinovo.numen.api.brain.LlmMind;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.keys.Keychain;
import com.dwinovo.numen.api.keys.Keys;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.library.Shipped;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.api.script.HerModules;
import com.dwinovo.numen.api.seat.SeatClient;
import com.dwinovo.numen.api.voice.VoiceProfile;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * 主人这台电脑上、连着一台服务器期间她的大脑要用的一切,由这一处接起来:她的上下文在这台电脑上她的家里,主人的库(人设、模型档案、声线、
 * 技能、接进来的 MCP 服务器)、钥匙串、偏好在 {@code <根>/} 下,模型走她绑的档案,工具是系统自带的加主人接进来的,她的模块在主人的库里。
 * 一个连接一份:连上时建,断线时 {@link #close}。产品与评测用同一个 {@link #create},只换根目录与执行体。
 *
 * <p>线程:大脑的线程({@code main})。
 */
public final class ClientServices {

    /** 随模组带来的、要同步进主人的库的几种:人设、模型档案、声线、接进来的 MCP 服务器。技能与内置模块在服务端,不在这里。 */
    private static final Set<String> SHIPPED = Set.of(PersonaLibrary.KIND.dir(), ProviderLibrary.KIND.dir(),
            VoiceProfile.KIND.dir(), McpClientConfig.KIND.dir());

    private final UUID player;
    private final Path root;
    private final Executor main;
    private final BooleanSupplier driving;
    private final ClientBrains brains;
    private final ClientPrefs prefs;
    private final LibraryHub hub;
    private final Keychain keychain;
    private final ModelCatalog models;
    private final ProviderLibrary providers;
    private final ClientMcp mcp;
    private final LibraryDesk desk;
    private final Home home;
    private final Home library;

    /**
     * @param player  这台电脑上的玩家
     * @param root    主人在这台电脑上的东西放哪({@code config/numen/client/})
     * @param seat    这条到服务器的座位连接
     * @param main    大脑的线程
     * @param driving 她的驾驶席在不在外接大脑手里(谁在驾驶在客户端一处定)
     */
    public ClientServices(UUID player, Path root, SeatClient seat, Executor main, BooleanSupplier driving) {
        this.player = player;
        this.root = root;
        this.main = main;
        this.driving = driving;
        com.dwinovo.numen.api.store.Io io = NumenIo.lanes().io("client");
        this.home = new Home(root, io);
        this.library = new Home(root.resolve("library"), io);
        this.prefs = new ClientPrefs(home);
        this.hub = LibraryHub.local(library, prefs, Shipped.bundles(), SHIPPED);
        this.keychain = new Keychain(new Home(root.resolve("keys"), io));
        this.models = new ModelCatalog(root.resolve("cache").resolve("models-dev.json"),
                () -> Services.config().getProxy());
        this.models.refresh();
        this.providers = new ProviderLibrary(hub.library(ProviderLibrary.KIND), new Keys(keychain), models);
        this.mcp = new ClientMcp(player, main, hub.library(McpClientConfig.KIND), keychain, () -> { });
        this.mcp.reconcile();
        this.brains = new ClientBrains(seat, main, companion -> brain(seat, companion));
        this.desk = new LibraryDesk(new LibraryDesk.Sources() {
            @Override
            public com.dwinovo.numen.api.library.Library library(com.dwinovo.numen.api.library.LibraryKind kind) {
                return hub.library(kind);
            }

            @Override
            public ProviderLibrary providers() {
                return providers;
            }

            @Override
            public ClientPrefs prefs() {
                return prefs;
            }

            @Override
            public List<LoopHost> brains() {
                return List.copyOf(ClientServices.this.brains.all());
            }

            @Override
            public Shipped.Manifest manifest() {
                return hub.manifest();
            }

            @Override
            public Keychain keys() {
                return keychain;
            }

            @Override
            public ClientMcp mcp() {
                return mcp;
            }
        }, models);
    }

    /** 一只同伴的大脑:家在她自己的目录里,模型是她绑的档案,人设正文每轮现取。 */
    private LoopHost brain(SeatClient seat, UUID companion) {
        PersonaLibrary personas = new PersonaLibrary(hub.library(PersonaLibrary.KIND));
        Home hers = new Home(root.resolve("companions").resolve(companion.toString()), home.io());
        return new LoopHost(new LoopHost.Parts(companion, player, hers, main, seat,
                new LlmMind(() -> providers, models), mcp, driving, prefs::initiative,
                (Bound bound) -> {
                    PersonaLibrary.Persona persona = personas.get(bound.id().toString());
                    return persona == null ? null : persona.text();
                },
                builtins -> new HerModules(library, builtins),
                bundled -> SkillRegistry.of(hub.library(SkillRegistry.KIND), bundled),
                who -> who.equals(player) ? "" : who.toString()));
    }

    /** 这条连接上的大脑。 */
    public ClientBrains brains() {
        return brains;
    }

    /** 主人这台电脑上的一种库。 */
    public com.dwinovo.numen.api.library.Library library(com.dwinovo.numen.api.library.LibraryKind kind) {
        return hub.library(kind);
    }

    /** 界面读写配置走这里。 */
    public LibraryDesk desk() {
        return desk;
    }

    /** 偏好。 */
    public ClientPrefs prefs() {
        return prefs;
    }

    /** 接进来的 MCP 服务器。 */
    public ClientMcp mcp() {
        return mcp;
    }

    /** 主人的钥匙串。 */
    public Keychain keychain() {
        return keychain;
    }

    /** 断线:大脑收掉,MCP 连接放手。 */
    public void close() {
        brains.leave();
        mcp.shutdown();
    }
}
