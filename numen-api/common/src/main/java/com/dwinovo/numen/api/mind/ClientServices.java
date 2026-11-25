package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.agent.llm.ModelCatalog;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.mind.LlmMind;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.keys.Keychain;
import com.dwinovo.numen.api.keys.Keys;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.library.Shipped;
import com.dwinovo.numen.api.mcp.server.ExternalHands;
import com.dwinovo.numen.api.mcp.server.McpMode;
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
import java.util.function.Supplier;

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
    private final ConsentVenues venues;
    private McpMode external;

    /**
     * 游戏这一侧给大脑的东西。
     *
     * @param onMain  调用的线程是不是客户端主线程
     * @param roster  主人名下此刻在世界里的同伴
     * @param actions 召唤与遣散
     * @param answers 征询的答复怎么发回服务端
     */
    public record Game(BooleanSupplier onMain, Supplier<List<ExternalHands.Companion>> roster, LoopHands.Actions actions,
                       ConsentVenues.Answers answers) {}

    /**
     * @param player  这台电脑上的玩家
     * @param root    主人在这台电脑上的东西放哪({@code config/numen/client/})
     * @param seat    这条到服务器的座位连接
     * @param main    大脑的线程
     * @param game    游戏这一侧给的东西
     */
    public ClientServices(UUID player, Path root, SeatClient seat, Executor main, Game game) {
        this.player = player;
        this.root = root;
        this.main = main;
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
        this.venues = new ConsentVenues(game.answers());
        this.brains = new ClientBrains(seat, main, companion -> brain(seat, companion));
        LoopHands hands = new LoopHands(brains, main, game.onMain(), mcp, game.roster(), game.actions(), venues);
        this.external = new McpMode(new Home(root.resolve("mcp"), io), player, hands, venues,
                Services.platform().modVersion(Constants.MOD_ID));
        this.external.start();
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

            @Override
            public McpMode external() {
                return external;
            }
        }, models);
    }

    /** 一只同伴的大脑:家在她自己的目录里,模型是她绑的档案,人设正文每轮现取。 */
    private LoopHost brain(SeatClient seat, UUID companion) {
        PersonaLibrary personas = new PersonaLibrary(hub.library(PersonaLibrary.KIND));
        Home hers = new Home(root.resolve("companions").resolve(companion.toString()), home.io());
        return new LoopHost(new LoopHost.Parts(companion, player, hers, main, seat,
                new LlmMind(() -> providers, models), mcp, () -> external.driving(companion), prefs::initiative,
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

    /** 外部大脑的端点:谁在驾驶、令牌、设置都在这里。 */
    public McpMode external() {
        return external;
    }

    /** 征询的去处:答复框之外的场所(外部大脑的客户端、渠道)。 */
    public ConsentVenues venues() {
        return venues;
    }

    /** 每个客户端刻一次:端点清扫过期的会话与令牌,各只的大脑往前走一步。 */
    public void tick() {
        external.tick();
        brains.tick();
    }

    /** 断线:大脑收掉,外部大脑的端点与 MCP 连接放手。 */
    public void close() {
        external.shutdown();
        venues.clearAll();
        brains.leave();
        mcp.shutdown();
    }
}
