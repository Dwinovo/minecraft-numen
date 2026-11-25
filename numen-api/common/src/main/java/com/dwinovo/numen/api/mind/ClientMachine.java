package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.agent.llm.ModelCatalog;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.keys.Keychain;
import com.dwinovo.numen.api.keys.Keys;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.library.LibraryKind;
import com.dwinovo.numen.api.library.Shipped;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.voice.VoiceProfile;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 这台电脑自己的东西:钥匙串、主人的各库(人设、模型档案、声线、技能、接进来的 MCP 服务器的配置)、偏好、型号目录,以及界面读写它们的口
 * ({@link LibraryDesk})。它们属于这台机器,不属于哪一条连接:设置页与语音在主菜单里、没连服务器时也要用,所以一个客户端进程只建一份,
 * 连接({@link ClientServices})来了用它、走了不带走它。钥匙串只此一个,语音与模型档案读同一个。
 *
 * <p>只有跟着连接才有的几样(大脑、接进来的 MCP 工具的连接、外部大脑的端点)由当前连接挂上来({@link #attach}),没连着时界面读到的是空的与"没连服务器"。
 *
 * <p>线程:大脑的线程(客户端主线程)。
 */
public final class ClientMachine {

    /** 随模组带来的、要同步进主人的库的几种:人设、模型档案、声线、接进来的 MCP 服务器。技能与内置模块在服务端,不在这里。 */
    private static final Set<String> SHIPPED = Set.of(PersonaLibrary.KIND.dir(), ProviderLibrary.KIND.dir(),
            VoiceProfile.KIND.dir(), McpClientConfig.KIND.dir());

    /** 跟着连接才有的东西。 */
    public interface Connection {

        /** 这条连接上她们的大脑。 */
        List<LoopHost> brains();

        /** 接进来的 MCP 服务器的连接。 */
        ClientMcp mcp();

        /** 外部大脑的端点。 */
        McpMode external();
    }

    private final Path root;
    private final Home home;
    private final Home library;
    private final ClientPrefs prefs;
    private final LibraryHub hub;
    private final Keychain keychain;
    private final ModelCatalog models;
    private final ProviderLibrary providers;
    private final LibraryDesk desk;
    private final AtomicReference<Connection> connection = new AtomicReference<>();

    /**
     * @param root 这台电脑上她的东西放哪({@code config/numen/client/})
     * @param io   读写放哪个线程
     */
    public ClientMachine(Path root, Io io) {
        this.root = root;
        this.home = new Home(root, io);
        this.library = new Home(root.resolve("library"), io);
        this.prefs = new ClientPrefs(home);
        this.hub = LibraryHub.local(library, prefs, Shipped.bundles(), SHIPPED);
        this.keychain = new Keychain(home);
        this.models = new ModelCatalog(root.resolve("cache").resolve("models-dev.json"),
                () -> Services.config().getProxy());
        this.models.refresh();
        this.providers = new ProviderLibrary(hub.library(ProviderLibrary.KIND), new Keys(keychain), models);
        this.desk = new LibraryDesk(new LibraryDesk.Sources() {
            @Override
            public Library library(LibraryKind kind) {
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
                Connection live = connection.get();
                return live == null ? List.of() : live.brains();
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
                Connection live = connection.get();
                return live == null ? null : live.mcp();
            }

            @Override
            public McpMode external() {
                Connection live = connection.get();
                return live == null ? null : live.external();
            }
        }, models);
    }

    /** 当前连接来了。 */
    public void attach(Connection live) {
        connection.set(live);
    }

    /** 这条连接走了。 */
    public void detach(Connection live) {
        connection.compareAndSet(live, null);
    }

    /** 主人这台电脑上的一种库。 */
    public Library library(LibraryKind kind) {
        return hub.library(kind);
    }

    /** 他的各库在磁盘上的位置(她的模块也放在这里)。 */
    public Home libraryHome() {
        return library;
    }

    /** 这台电脑上她的东西的根。 */
    public Home home() {
        return home;
    }

    /** 这台电脑上她的东西的根目录。 */
    public Path root() {
        return root;
    }

    /** 偏好。 */
    public ClientPrefs prefs() {
        return prefs;
    }

    /** 这台电脑的钥匙串:模型档案、声线、语音识别、接进来的 MCP 服务器的 key 都在这一个里。 */
    public Keychain keychain() {
        return keychain;
    }

    /** 型号目录。 */
    public ModelCatalog models() {
        return models;
    }

    /** 模型档案库。 */
    public ProviderLibrary providers() {
        return providers;
    }

    /** 界面读写配置走这里。 */
    public LibraryDesk desk() {
        return desk;
    }
}
