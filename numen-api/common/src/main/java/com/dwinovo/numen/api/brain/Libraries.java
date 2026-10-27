package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.mcp.client.McpServerSpec;
import com.dwinovo.numen.api.agent.llm.ConnectionProfile;
import com.dwinovo.numen.api.agent.llm.ModelCatalog;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.dwinovo.numen.api.agent.skill.SkillInfo;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.keys.Keychain;
import com.dwinovo.numen.api.keys.Keys;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.library.LibraryKind;
import com.dwinovo.numen.api.library.Shelf;
import com.dwinovo.numen.api.library.Shipped;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.mcp.client.McpConnections;
import com.dwinovo.numen.mcp.client.auth.McpOAuth;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.permission.PermissionConfig;
import com.dwinovo.numen.api.permission.PermissionLibrary;
import com.dwinovo.numen.api.permission.PermissionStore;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.plugin.PluginRoster;
import com.dwinovo.numen.api.skin.MineSkinClient;
import com.dwinovo.numen.api.skin.SkinError;
import com.dwinovo.numen.api.skin.SkinFolder;
import com.dwinovo.numen.api.skin.SkinSigning;
import com.dwinovo.numen.api.skin.Wardrobe;
import com.dwinovo.numen.api.voice.VoiceProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 服务器上的库对主人客户端的读写口:列、存、删,每种库自己说谁能做什么——判据只在这一处,界面和工具里不再另写。
 *
 * <p><b>内容库</b>({@link #PERSONA}、{@link #PROVIDER}、{@link #SKILL},接进来的 MCP 工具见 {@link #MCP_CLIENT})都是
 * {@link Library}:一位玩家的"我的"与"服务器提供"两组,同名时我的优先。归属只有一句话:<b>房主自己这台机器上的库,对房主是"我的",
 * 对别人是"服务器提供"</b>——单人游戏与局域网开房的房主,他的"我的"就是服务器目录那一份,只有一节;专用服务器与来串门的玩家,
 * "服务器提供"是服务器目录(管理员能改),"我的"是他自己的目录(本人能改)。判"是不是房主"只在 {@code Homes#isHost}。
 * 同一套操作适用于它们:{@link #PUT}、{@link #DELETE}(带 {@code group: "server"} 的只有管理员能做)、{@link #COPY}(服务器提供的复制到我的)、
 * {@link #ENABLE}/{@link #DISABLE}(玩家只读服务器提供的,开关记在他自己的偏好里)、{@link #ACCEPT}/{@link #DISMISS}(管理员处理 jar 带来的新版)、{@link #RESTORE}(管理员把改过的自带内容丢掉改动、取回 jar 里的默认)、
 * {@link #RELOAD}(重扫目录)。每一行带 {@code group}、{@code enabled},服务器目录里的带"是不是 jar 自带的、改过没有、有没有新版、来源卸载了没有"。
 * <ul>
 *   <li><b>模型档案</b>:key 不在档案里。管理员看到服务器提供的档案的全貌;别人只看得到服务器提供的档案"叫什么、哪个厂家、哪个型号"——地址与代理在服务端
 *       编码的这一处就没有;自己的档案是完整的;</li>
 *   <li><b>钥匙串</b>({@link #KEY}):按服务商存 key。玩家看得到、改得了自己的;服主的只有管理员看得到、改得了,别人只知道有没有;</li>
 *   <li><b>型号目录</b>({@link #CATALOG})与<b>连通性检测</b>({@link #PROVIDER_CHECK}):档案表单问的,目录、草稿解析成的端点、检测的那次请求
 *       都在服务端算({@link ProviderForm}),界面只画答案;</li>
 *   <li><b>声线</b>({@link #VOICE})也是内容库:条目是服务商、音色、参数,不含 key(key 在主人本机的钥匙串里,合成在他的客户端上);</li>
 *   <li><b>皮肤</b>({@link #SKIN})<b>不是内容库</b>:皮肤是文件夹里的 png。这一种只是给同伴换皮肤的口:表里是他在签名队里的几行,
 *       服务器文件夹里的图是另一张只读的表({@link #SKIN_FILES},房主没有,他的文件夹客户端自己列);{@link #PUT} 带图(他本机文件夹里的)、服务器文件夹里的文件名、网址或
 *       正版玩家名,图签过的当场穿上,没签过的在服务端排队去签({@link SkinSigning},用时才签、签一次存永久),排到第几、被限流在等、
 *       失败的原因都作为表里的几行({@code row: "pending"})交给他;{@link #DELETE} 脱掉;{@link #CANCEL} 撤掉排着的或不再显示失败的;</li>
 *   <li><b>服务器配置</b>(代理、默认人设):只有管理员。</li>
 *   <li><b>偏好</b>({@link #PREFS}):每位主人自己的,自己读写;</li>
 *   <li><b>她的札记</b>({@link #NOTES}):只读,主人名下各只的札记索引(名字、一句话、类型、哪天);全文要她自己 recall;</li>
 *   <li><b>权限</b>({@link #PERMISSION}):主人自己的规则与他名下每只同伴的模式,只有他读写他那一份(管理员也一样),
 *       见 {@link PermissionLibrary};</li>
 *   <li><b>外接大脑</b>({@link #MCP_SERVER}):接入地址、令牌、局域网、开关都是管理员的事,别人看到的是空表;</li>
 *   <li><b>接进来的 MCP 服务器</b>({@link #MCP_CLIENT}):地址、令牌、命令行都是管理员的事(stdio 起的是服务器机器上的进程),
 *       别人只看得到名字、连没连上、有几个工具。授权网址只给管理员,授权回来的网址也由管理员贴回({@link #AUTH})。</li>
 * </ul>
 * 答复永远是这位玩家此刻看得到的整表,存和删之后也是;办不成附上原因。
 *
 * <p>线程:服务端主线程。
 */
public final class Libraries {

    public static final String PERSONA = "persona";
    public static final String PROVIDER = "provider";
    public static final String SERVER_CONFIG = "server-config";
    public static final String MCP_CLIENT = "mcp-client";
    public static final String MCP_SERVER = "mcp-server";
    public static final String PREFS = "prefs";
    public static final String SKILL = "skill";
    public static final String NOTES = "notes";
    public static final String CATALOG = "catalog";
    public static final String PROVIDER_CHECK = "provider-check";
    public static final String KEY = "key";
    public static final String VOICE = "voice";
    public static final String SKIN = "skin";
    /** 服务器皮肤文件夹里的图:只读,客户端要看时才问(带着每张图,不随排队的进度一起推)。 */
    public static final String SKIN_FILES = "skin-files";
    public static final String PERMISSION = "permission";
    /** 插件花名册:人人读;开关(记在服务器的停用名单里,下次启动生效)只有管理员与房主能改,用 {@link #ENABLE}/{@link #DISABLE}。 */
    public static final String PLUGIN = "plugin";

    public static final String LIST = "list";
    public static final String PUT = "put";
    public static final String DELETE = "delete";
    /** 内容库专有:重扫目录。 */
    public static final String RELOAD = "reload";
    /** 内容库专有:把服务器提供的一条原样复制到我的。 */
    public static final String COPY = "copy";
    /** 内容库专有:玩家自己的开关(记在他的偏好里)。 */
    public static final String ENABLE = "enable";
    public static final String DISABLE = "disable";
    /** MCP 服务器专有:条目主人的"连不连"(服务器提供的只有管理员)。 */
    public static final String CONNECT = "connect";
    public static final String DISCONNECT = "disconnect";
    /** 内容库专有,管理员:jar 带来了新版而他改过自带内容时,采用新版(换掉他的)或不采用(这一版不再提示)。 */
    public static final String ACCEPT = "accept";
    public static final String DISMISS = "dismiss";
    /** 内容库专有,管理员:改过的自带条目丢掉改动,取回 jar 里的默认。 */
    public static final String RESTORE = "restore";
    /** MCP 服务器专有:交回授权回调网址。 */
    public static final String AUTH = "auth";
    /** 权限专有:清空主人层的规则;设一只同伴的模式。加一行、删一行用 {@link #PUT}、{@link #DELETE}。 */
    public static final String RESET = PermissionLibrary.RESET;
    public static final String MODE = PermissionLibrary.MODE;
    /** 连通性检测专有:试连一份草稿。 */
    public static final String RUN = "run";
    /** 皮肤专有:撤掉一张排着的签名(或不再显示一条失败)。 */
    public static final String CANCEL = "cancel";

    /** 新建成功的答复里附的一行 {@code {"created": 新条目的 id}};改已有条目的答复不带。 */
    public static final String CREATED = "created";

    /** 请求里选哪一组:缺省是我的;{@code "server"} 是服务器提供(只有管理员)。 */
    public static final String GROUP_SERVER = "server";
    public static final String GROUP_MINE = "mine";

    /** 多人服上玩家第一次存自己的 key 时,答复表里多一行 {@code {"notice": …}} 提醒他服主看得到这把 key。 */
    public static final String NOTICE_OWNER_SEES_KEY = "owner_sees_key";

    /** 服务器配置里这两项的读写(加载器自己的配置文件)。 */
    public interface ServerConfig {
        String proxy();

        String systemPrompt();

        /** 存并落盘。 */
        void set(String proxy, String systemPrompt);
    }

    /**
     * 一次读写的结果。
     *
     * @param entries 这位玩家此刻看得到的整表(JSON 数组)
     * @param problem 办不成的原因(给界面和日志);办成是 {@code null}
     * @param later   还要等一会儿才有的结局(连通性检测的那次请求):到了再作为同一种库的又一张表送出去(请求号随这一张,它才是答复);没有是 {@code null}
     * @param shared  这次改动改了所有人共用的东西(服务器目录、服主的设置):在线的其他玩家各收到他们看得到的新表
     */
    public record Result(String entries, String problem, java.util.concurrent.CompletableFuture<String> later,
                         boolean shared) {

        public Result(String entries, String problem) {
            this(entries, problem, null, false);
        }
    }

    /** 这台服务器上各库的出处;测试给临时目录里的。 */
    public interface Sources {

        /** 这位玩家的这种内容库(服务器提供加我的;房主只有我的)。 */
        Library library(LibraryKind kind, UUID owner);

        /** 这位玩家的模型档案库。 */
        ProviderLibrary providers(UUID owner);

        OwnerPrefs prefs(UUID owner);

        /** 主人名下的同伴。 */
        List<UUID> companions(UUID owner);

        /** 服务器目录里 jar 同步的清单。 */
        Shipped.Manifest manifest();

        Keychain ownerKeys(UUID owner);

        Keychain serverKeys();

        /** 这位主人的权限:他的规则存档、服主的开关、他名下的同伴。 */
        PermissionLibrary.Ground permission(UUID owner);

        /** 服务器那一处 MCP 连接;没有就是 {@code null}。 */
        McpConnections sharedMcp();

        /** 这位玩家自己那一处 MCP 连接;没有就是 {@code null}。 */
        McpConnections ownerMcp(UUID owner);

        /** MCP 库变了:连接跟上。 */
        void reconcileMcp(UUID owner);

        /** 这位玩家是不是房主:他的"我的"各库与钥匙串就是服务器目录那一份。 */
        boolean host(UUID owner);

        /** 是不是多人服(单人游戏里服主就是玩家自己,不必提醒他看得到自己的 key)。 */
        boolean multiplayer();

        ServerConfig config();

        /** 这台服务器上的皮肤签名队(排队的状态、撤掉)。 */
        SkinSigning signer();

        /** 给同伴换皮肤的流程,连同服务器上的皮肤文件夹。 */
        Wardrobe wardrobe();

        /** 这台服务器上的插件花名册。 */
        PluginRoster plugins();
    }

    /** 这台服务器上的那一份。 */
    public static Libraries of(net.minecraft.server.MinecraftServer server) {
        LibraryHub hub = LibraryHub.of(server);
        ModelServices models = ModelServices.of(server);
        return new Libraries(new Sources() {
            private final com.dwinovo.numen.api.platform.services.INumenConfig config =
                    com.dwinovo.numen.api.platform.Services.config();

            @Override
            public Library library(LibraryKind kind, UUID owner) {
                return hub.library(kind, owner);
            }

            @Override
            public ProviderLibrary providers(UUID owner) {
                return models.providers(owner);
            }

            @Override
            public OwnerPrefs prefs(UUID owner) {
                return OwnerPrefs.of(server, owner);
            }

            @Override
            public List<UUID> companions(UUID owner) {
                return CompanionRegistry.get(server).ownedBy(owner).stream().map(java.util.Map.Entry::getKey).toList();
            }

            @Override
            public Shipped.Manifest manifest() {
                return hub.manifest();
            }

            @Override
            public Keychain ownerKeys(UUID owner) {
                return models.ownerKeychain(owner);
            }

            @Override
            public Keychain serverKeys() {
                return models.serverKeychain();
            }

            @Override
            public PermissionLibrary.Ground permission(UUID owner) {
                return new PermissionLibrary.Ground() {
                    @Override
                    public PermissionStore store() {
                        return PermissionStore.of(server, owner);
                    }

                    @Override
                    public boolean hardRules() {
                        return PermissionConfig.of(server).hardRules();
                    }

                    @Override
                    public Map<UUID, String> companions() {
                        Map<UUID, String> out = new LinkedHashMap<>();
                        CompanionRegistry.get(server).ownedBy(owner).forEach(e -> out.put(e.getKey(), e.getValue().name()));
                        return out;
                    }
                };
            }

            @Override
            public McpConnections sharedMcp() {
                return McpClients.of(server).shared();
            }

            @Override
            public McpConnections ownerMcp(UUID owner) {
                return McpClients.of(server).owner(owner);
            }

            @Override
            public void reconcileMcp(UUID owner) {
                McpClients.of(server).reconcile(owner);
            }

            @Override
            public boolean host(UUID owner) {
                return Homes.of(server).isHost(owner);
            }

            @Override
            public boolean multiplayer() {
                return !server.isSingleplayer();
            }

            @Override
            public SkinSigning signer() {
                return ServerSkins.of(server).signer();
            }

            @Override
            public Wardrobe wardrobe() {
                return ServerSkins.of(server).wardrobe();
            }

            @Override
            public PluginRoster plugins() {
                return NumenPlugins.roster();
            }

            @Override
            public ServerConfig config() {
                return new ServerConfig() {
                    @Override
                    public String proxy() {
                        return config.getProxy();
                    }

                    @Override
                    public String systemPrompt() {
                        return config.getSystemPrompt();
                    }

                    @Override
                    public void set(String proxy, String systemPrompt) {
                        config.setProxy(proxy);
                        config.setSystemPrompt(systemPrompt);
                        config.save();
                    }
                };
            }
        }, models.catalog());
    }

    private final Sources sources;
    /** 型号目录与连通性检测问的目录。 */
    private final ModelCatalog catalog;

    public Libraries(Sources sources, ModelCatalog catalog) {
        this.sources = sources;
        this.catalog = catalog;
    }

    /**
     * @param owner 发请求的玩家
     * @param admin 他是不是管理员({@code Authority.isAdmin})
     * @param body  存和删带的 JSON;列不用
     */
    public Result handle(UUID owner, boolean admin, String kind, String op, JsonObject body) {
        if (PROVIDER_CHECK.equals(kind)) {
            return check(owner, op, body);
        }
        Done done = switch (kind) {
            case PERSONA -> contentLibrary(owner, admin, PersonaLibrary.KIND, op, body,
                    b -> required(b, "text", "a persona needs a text"),
                    (from, to) -> CompanionLoops.rebind(owner, Slot.PERSONA, from.toString(), to.toString()));
            case PROVIDER -> contentLibrary(owner, admin, ProviderLibrary.KIND, op, body,
                    b -> ProviderLibrary.write(ProviderLibrary.profileFromJson(b)),
                    (from, to) -> CompanionLoops.rebind(owner, Slot.PROVIDER, from.toString(), to.toString()));
            case SKILL -> contentLibrary(owner, admin, SkillRegistry.KIND, op, body, null, (from, to) -> { });
            case VOICE -> contentLibrary(owner, admin, VoiceProfile.KIND, op, body,
                    b -> VoiceProfile.fromJson(b).write(),
                    (from, to) -> CompanionLoops.rebind(owner, Slot.VOICE, from.toString(), to.toString()));
            case SKIN -> skin(owner, op, body);
            case KEY -> key(owner, admin, op, body);
            case PERMISSION -> permission(owner, op, body);
            case SERVER_CONFIG -> Done.of(serverConfig(admin, op, body), true);
            case MCP_CLIENT -> mcpClient(owner, admin, op, body);
            case MCP_SERVER -> Done.of(mcpServer(admin, op, body), true);
            case PLUGIN -> Done.of(plugin(admin || sources.host(owner), op, body), true);
            case PREFS -> Done.of(prefs(owner, op, body), false);
            case NOTES, CATALOG, SKIN_FILES -> Done.of(LIST.equals(op) ? null : "unknown operation", false);
            default -> Done.of("unknown library", false);
        };
        JsonArray entries = JsonParser.parseString(view(owner, admin, kind, body)).getAsJsonArray();
        if (done.extra() != null) {
            entries.add(done.extra());
        }
        return new Result(entries.toString(), done.problem(), null, done.shared() && done.problem() == null);
    }

    /** 一次操作的结局:办不成的原因,是不是改了所有人共用的东西,以及要随答复附在整表后面带给这位玩家的一行(提醒、规则错在哪)。 */
    private record Done(String problem, boolean shared, JsonObject extra) {

        static Done ok() {
            return new Done(null, false, null);
        }

        static Done of(String problem, boolean shared) {
            return new Done(problem, shared, null);
        }

        static Done problem(String problem) {
            return new Done(problem, false, null);
        }
    }

    /** 连通性检测:试连一份草稿,先答"在试",结局到了再送一张表。用的 key 是草稿里正在输入的,或这位玩家钥匙串里这个服务的。 */
    private Result check(UUID owner, String op, JsonObject body) {
        if (!RUN.equals(op)) {
            return new Result("[]", "unknown operation");
        }
        if (!body.has("draft") || !body.get("draft").isJsonObject()) {
            return new Result("[]", "a connection test needs the draft to test");
        }
        JsonArray checking = new JsonArray();
        checking.add(ProviderForm.state(ProviderForm.STATE_CHECKING));
        return new Result(checking.toString(), null,
                ProviderForm.check(catalog, sources.providers(owner).keys(), body).thenApply(state -> {
                    JsonArray done = new JsonArray();
                    done.add(state);
                    return done.toString();
                }), false);
    }

    /** 这位玩家此刻看得到的整表。看不到的库是空表。 */
    public String view(UUID owner, boolean admin, String kind) {
        return view(owner, admin, kind, new JsonObject());
    }

    /** 同上;目录这种按问题答的库,{@code ask} 是问题(见 {@link ProviderForm#answer})。 */
    private String view(UUID owner, boolean admin, String kind, JsonObject ask) {
        JsonArray out = new JsonArray();
        switch (kind) {
            case PERSONA -> rows(sources.library(PersonaLibrary.KIND, owner), out, (entry, row) ->
                    row.addProperty("text", entry.text().strip()));
            case PROVIDER -> providerRows(owner, admin, out);
            case SKILL -> rows(sources.library(SkillRegistry.KIND, owner), out, (entry, row) -> {
                SkillInfo info = SkillRegistry.read(entry.path());
                row.addProperty("skill", info == null ? entry.id().name() : info.name());
                row.addProperty("description", info == null || info.description() == null ? "" : info.description());
            });
            case VOICE -> rows(sources.library(VoiceProfile.KIND, owner), out, (entry, row) -> {
                try {
                    VoiceProfile.read(entry.text()).toJson().entrySet().forEach(e -> row.add(e.getKey(), e.getValue()));
                } catch (RuntimeException unreadable) {
                    row.addProperty("unreadable", true);
                }
            });
            case SKIN -> pendingRows(owner, out);
            case SKIN_FILES -> fileRows(owner, out);
            case KEY -> keyRows(owner, admin, out);
            case SERVER_CONFIG -> {
                if (admin) {
                    JsonObject o = new JsonObject();
                    o.addProperty("proxy", sources.config().proxy());
                    o.addProperty("systemPrompt", sources.config().systemPrompt());
                    out.add(o);
                }
            }
            case MCP_CLIENT -> mcpRows(owner, admin, out);
            case PLUGIN -> pluginRows(admin || sources.host(owner), out);
            case MCP_SERVER -> {
                if (admin) {
                    out.add(mcpServerView());
                }
            }
            case CATALOG -> out.add(ProviderForm.answer(catalog, ask));
            case PREFS -> out.add(sources.prefs(owner).toJson());
            case PERMISSION -> out.addAll(PermissionLibrary.view(sources.permission(owner)));
            case NOTES -> sources.companions(owner).forEach(companion -> NoteBook.of(companion).index().forEach(note -> {
                JsonObject o = new JsonObject();
                o.addProperty("companion", companion.toString());
                o.addProperty("name", note.name());
                o.addProperty("description", note.description());
                o.addProperty("type", note.type());
                o.addProperty("day", note.day());
                out.add(o);
            }));
            default -> { }
        }
        return out.toString();
    }

    // ---- 内容库:同一套操作、同一种行 ----

    /** 一条的内容库行共有的几项;{@code extra} 补各库自己的。服务器提供的在前,我的在后。 */
    private static void rows(Library library, JsonArray out,
                             java.util.function.BiConsumer<Library.Entry, JsonObject> extra) {
        for (Library.Group group : Library.Group.values()) {
            for (Library.Entry entry : library.group(group)) {
                JsonObject row = new JsonObject();
                row.addProperty("id", entry.id().toString());
                row.addProperty("name", entry.id().name());
                row.addProperty("namespace", entry.id().namespace());
                row.addProperty("group", group == Library.Group.SERVER && !library.merged() ? GROUP_SERVER : GROUP_MINE);
                row.addProperty("shipped", entry.provenance().shipped());
                row.addProperty("enabled", entry.enabled());
                row.addProperty("paired", entry.paired());
                row.addProperty("modified", entry.provenance().modified());
                row.addProperty("update", entry.provenance().updateAvailable());
                row.addProperty("gone", entry.provenance().sourceGone());
                extra.accept(entry, row);
                out.add(row);
            }
        }
    }

    /** 一条内容库的正文从请求里取出来(文件的正文);写法不对抛 {@link IllegalArgumentException},原因给界面。 */
    private interface Content {
        String text(JsonObject body);
    }

    private static String required(JsonObject body, String key, String problem) {
        String text = string(body, key);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(problem);
        }
        return text;
    }

    /**
     * 内容库的操作。写和删带 {@code group: "server"} 的是改服务器目录里的,只有管理员;默认是改我的(房主的"我的"就是服务器目录)。
     * 服务器提供的一条玩家只读,
     * 想改就 {@link #COPY} 到我的再改;开关记在他自己的偏好里。
     *
     * @param content 请求的正文变成文件的正文;这种库不能经协议存就是 {@code null}
     * @param renamed 我的某一条改了名(id 随之更换)时,绑着旧 id 的同伴改指新的
     */
    private Done contentLibrary(UUID owner, boolean admin, LibraryKind kind, String op, JsonObject body, Content content,
                                java.util.function.BiConsumer<EntryId, EntryId> renamed) {
        Library library = sources.library(kind, owner);
        boolean onServer = GROUP_SERVER.equals(string(body, "group"));
        EntryId id = EntryId.parse(string(body, "id"));
        switch (op) {
            case LIST:
                return Done.ok();
            case RELOAD:
                library.reload();   // 目录里的文件是真源,外部编辑器改过就重扫
                return Done.ok();
            case COPY:
                return library.copyToMine(id) == null ? Done.problem("no such server entry") : Done.ok();
            case ENABLE:
            case DISABLE:
                if (id == null || library.findAny(id) == null) {
                    return Done.problem("no such entry");
                }
                library.setEnabled(id, ENABLE.equals(op));
                return Done.ok();
            case ACCEPT:
            case DISMISS:
                if (!admin) {
                    return Done.problem("only a server admin may take or dismiss a new default version");
                }
                if (id == null || library.server().get(id) == null) {
                    return Done.problem("no such server entry");
                }
                if (ACCEPT.equals(op)) {
                    sources.manifest().accept(kind, id);
                } else {
                    sources.manifest().dismiss(kind, id);
                }
                library.server().reload();   // 清单换了磁盘上的文件,内存里的表跟上
                return Done.of(null, true);
            case RESTORE:
                if (!admin) {
                    return Done.problem("only a server admin may restore a shipped entry to its default");
                }
                if (id == null || library.server().get(id) == null) {
                    return Done.problem("no such server entry");
                }
                if (!sources.manifest().restore(kind, id)) {
                    return Done.problem("that entry has no default to go back to");
                }
                library.server().reload();   // 清单换了磁盘上的文件,内存里的表跟上
                return Done.of(null, true);
            case PUT:
            case DELETE:
                break;
            default:
                return Done.problem("unknown operation");
        }
        if (onServer && !admin) {
            return Done.problem("only a server admin may change the server's entries");
        }
        Shelf shelf = onServer ? library.server() : library.mine();
        if (DELETE.equals(op)) {
            if (id == null || shelf.get(id) == null) {
                return Done.problem("no such entry");
            }
            shelf.delete(id);
            return Done.of(null, shelf == library.server());
        }
        if (content == null) {
            return Done.problem("unknown operation");
        }
        String name = string(body, "name");
        if (name == null || name.isBlank()) {
            return Done.problem("an entry needs a name");
        }
        String text;
        try {
            text = content.text(body);
        } catch (IllegalArgumentException unreadable) {
            return Done.problem(unreadable.getMessage());
        }
        String clean = EntryId.clean(name, kind.dir());
        EntryId old = id != null && shelf.get(id) != null ? id : null;
        EntryId target = old != null && old.name().equals(clean) ? old
                : shelf.unique(old != null ? old.namespace() : onServer ? EntryId.SERVER : EntryId.MINE, clean);
        shelf.write(target, text);
        if (old != null && !old.equals(target)) {
            shelf.delete(old);
            if (!onServer) {
                renamed.accept(old, target);   // 我的某条改了名:id 就是名字,在用它的同伴得重新指过去
            }
        }
        if (old != null) {
            return Done.of(null, shelf == library.server());
        }
        // 新建的:id 由服务端按名字起,客户端要凭它把新条目绑给当前同伴,所以随答复明说
        JsonObject created = new JsonObject();
        created.addProperty(CREATED, target.toString());
        return new Done(null, shelf == library.server(), created);
    }

    // ---- 皮肤:文件夹里的 png,用时才签 ----

    /**
     * 服务器皮肤文件夹里的图({@code row: "file"}:名字、原图 base64,图不能用的带原因码)。房主的"我的"文件夹就是服务器那一个,
     * 客户端自己列,这里不再重复给他一节"服务器提供",是空表。
     */
    private void fileRows(UUID owner, JsonArray out) {
        if (sources.host(owner)) {
            return;
        }
        SkinFolder folder = sources.wardrobe().folder();
        for (SkinFolder.Entry file : folder.list()) {
            JsonObject row = new JsonObject();
            row.addProperty("row", "file");
            row.addProperty("name", file.name());
            try {
                row.addProperty("png", java.util.Base64.getEncoder().encodeToString(folder.read(file.name())));
            } catch (SkinError unusable) {
                row.add("problem", skinProblem(unusable));
            }
            out.add(row);
        }
    }

    /** 这位玩家在签名队里的与刚失败的({@code row: "pending"},带排到第几、在等限流、失败的原因码与原话)。 */
    private void pendingRows(UUID owner, JsonArray out) {
        for (SkinSigning.Pending pending : sources.signer().pendingOf(owner)) {
            JsonObject row = new JsonObject();
            row.addProperty("row", "pending");
            row.addProperty("name", pending.name());
            row.addProperty("job", pending.id());
            row.addProperty("state", pending.failure() != null ? "FAILED" : pending.state().name());
            row.addProperty("position", pending.position());
            if (pending.failure() != null) {
                row.add("problem", skinProblem(pending.failure()));
            }
            out.add(row);
        }
    }

    private static JsonObject skinProblem(SkinError error) {
        JsonObject problem = new JsonObject();
        problem.addProperty("code", error.code());
        JsonArray args = new JsonArray();
        error.args().forEach(args::add);
        problem.add("args", args);
        return problem;
    }

    /**
     * 皮肤的操作,都对他名下的一只同伴({@code companion})。存({@link #PUT})是给她换上这张皮肤,图是 {@code png}(base64,他本机文件夹里的)、
     * {@code file}(服务器文件夹里的文件名)、{@code url} 或 {@code player} 之一,{@code variant} 是手臂模型(不写是看图判断):签过的当场穿上,
     * 没签过的排上队,排到第几、被限流在等、失败的原因都在表里({@link #pendingRows})。删({@link #DELETE})是脱掉,回到默认皮肤。
     * 撤({@link #CANCEL})撤掉自己排着的或不再显示失败的。皮肤不是库里的条目,没有"复制到我的"这类操作。
     */
    private Done skin(UUID owner, String op, JsonObject body) {
        switch (op) {
            case LIST:
                return Done.ok();
            case CANCEL:
                JsonElement job = body.get("job");
                return job != null && job.isJsonPrimitive() && sources.signer().cancel(owner, job.getAsLong())
                        ? Done.ok() : Done.problem("no such pending skin");
            case PUT:
            case DELETE:
                break;
            default:
                return Done.problem("unknown operation");
        }
        String companionText = string(body, "companion");
        UUID companion;
        try {
            companion = companionText == null ? null : UUID.fromString(companionText);
        } catch (IllegalArgumentException notAUuid) {
            companion = null;
        }
        if (companion == null || !sources.companions(owner).contains(companion)) {
            return Done.problem("not your companion");
        }
        if (DELETE.equals(op)) {
            sources.wardrobe().takeOff(companion);
            return Done.ok();
        }
        String variant = string(body, "variant");
        String name = string(body, "name") != null ? string(body, "name") : "skin";
        try {
            long ticket;
            if (string(body, "png") != null) {
                byte[] png;
                try {
                    png = java.util.Base64.getDecoder().decode(string(body, "png"));
                } catch (IllegalArgumentException notBase64) {
                    return Done.problem("unreadable image");
                }
                ticket = sources.wardrobe().wear(owner, companion, name, new SkinSigning.Source.Image(png), variant,
                        outcome -> { });
            } else if (string(body, "file") != null) {
                ticket = sources.wardrobe().wearFile(owner, companion, string(body, "file"), variant, outcome -> { });
            } else if (string(body, "url") != null) {
                ticket = sources.wardrobe().wear(owner, companion, name, new SkinSigning.Source.Url(string(body, "url")),
                        variant, outcome -> { });
            } else if (string(body, "player") != null) {
                ticket = sources.wardrobe().wear(owner, companion, string(body, "player"),
                        new SkinSigning.Source.Player(string(body, "player")), variant, outcome -> { });
            } else {
                return Done.problem("a skin needs an image, a file, a url or a player name");
            }
            return ticket < 0 ? Done.problem("too many skins are waiting to be signed") : Done.ok();
        } catch (SkinError refused) {
            return Done.problem(refused.getMessage());
        }
    }

    // ---- 模型档案与钥匙串 ----

    /**
     * 模型档案的行。管理员看到服务器提供的档案的全貌;别人只看得到服务器提供的档案叫什么、哪个厂家、哪个型号——地址与代理在这里就没有,客户端
     * 拿不到也就谈不上泄露;自己的档案是完整的。每行说这位玩家有没有可用的 key、是他自己的还是服主的。
     */
    private void providerRows(UUID owner, boolean admin, JsonArray out) {
        Keys keys = sources.providers(owner).keys();
        rows(sources.library(ProviderLibrary.KIND, owner), out, (entry, row) -> {
            ConnectionProfile profile;
            try {
                profile = ProviderLibrary.read(entry.text());
            } catch (RuntimeException unreadable) {
                row.addProperty("unreadable", true);
                return;
            }
            boolean full = admin || entry.group() == Library.Group.MINE;
            JsonObject fields = full ? ProviderLibrary.profileToJson(profile) : masked(profile);
            fields.entrySet().forEach(e -> row.add(e.getKey(), e.getValue()));
            Keys.Found found = keys.find(profile.provider());
            row.addProperty("keySource", found == null ? "" : found.source() == Keys.Source.OWNER ? GROUP_MINE : "server");
        });
    }

    private static JsonObject masked(ConnectionProfile profile) {
        JsonObject o = new JsonObject();
        if (profile.provider() != null) {
            o.addProperty("provider", profile.provider());
        }
        if (profile.model() != null) {
            o.addProperty("model", profile.model());
        }
        return o;
    }

    /** 这位玩家的钥匙串页与 {@code /numen key} 补全列的服务商:他档案里用到的、已存过 key 的;管理员再加服主存过的与皮肤签名用的。 */
    public Set<String> keyServices(UUID owner, boolean admin) {
        Set<String> services = new TreeSet<>();
        Library providers = sources.library(ProviderLibrary.KIND, owner);
        for (Library.Group group : Library.Group.values()) {
            for (Library.Entry entry : providers.group(group)) {
                try {
                    String service = ProviderLibrary.read(entry.text()).provider();
                    if (service != null && EntryId.segment(service)) {
                        services.add(service);
                    }
                } catch (RuntimeException unreadable) {
                    // 读不通的档案没有服务可言
                }
            }
        }
        services.addAll(sources.ownerKeys(owner).services());
        if (admin) {
            services.addAll(sources.serverKeys().services());
            services.add(MineSkinClient.KEY_SERVICE);   // 皮肤签名用的,管理员在钥匙串页填
        }
        return services;
    }

    /**
     * 钥匙串:这位玩家用得到的那些服务商,各自有没有他自己的 key、服主有没有。服主的 key 本身只给管理员。房主的钥匙就是服主的
     * 那一本,只有"他自己的"一份,没有"服主的"可言。
     */
    private void keyRows(UUID owner, boolean admin, JsonArray out) {
        boolean host = sources.host(owner);
        for (String service : keyServices(owner, admin)) {
            JsonObject row = new JsonObject();
            row.addProperty("service", service);
            String mine = sources.ownerKeys(owner).get(service);
            if (mine != null) {
                row.addProperty("mine", mine);
            }
            if (!host) {
                String shared = sources.serverKeys().get(service);
                row.addProperty("serverSet", shared != null);
                if (admin && shared != null) {
                    row.addProperty("server", shared);
                }
            }
            out.add(row);
        }
    }

    /**
     * 存、删一把 key。自己的人人能改;服主的({@code scope: "server"})只有管理员。多人服上玩家第一次存自己的 key,答复里
     * 多一句提醒:服主看得到这把 key,请用设了花费上限的那一个。
     */
    private Done key(UUID owner, boolean admin, String op, JsonObject body) {
        if (LIST.equals(op)) {
            return Done.ok();
        }
        if (!PUT.equals(op) && !DELETE.equals(op)) {
            return Done.problem("unknown operation");
        }
        String service = string(body, "service");
        if (service == null || !EntryId.segment(service)) {
            return Done.problem("a key needs the service it is for");
        }
        boolean server = "server".equals(string(body, "scope"));
        if (server && !admin) {
            return Done.problem("only a server admin may change the server's keys");
        }
        String key = DELETE.equals(op) || string(body, "key") == null ? "" : string(body, "key");
        if (server) {
            sources.serverKeys().put(service, key);
            return Done.ok();
        }
        if (!saveOwnKey(owner, service, key)) {
            return Done.ok();
        }
        JsonObject notice = new JsonObject();
        notice.addProperty("notice", NOTICE_OWNER_SEES_KEY);
        return new Done(null, false, notice);
    }

    /**
     * 存下(空 key 是删掉)他自己钥匙串里的一把。多人服上他第一次存,要提醒他:服主看得到这把 key,请用设了花费上限的那一个——
     * 面板的钥匙串页与 {@code /numen key} 同走这一处。
     *
     * @return 要不要现在提醒他
     */
    public boolean saveOwnKey(UUID owner, String service, String key) {
        sources.ownerKeys(owner).put(service, key);
        OwnerPrefs prefs = sources.prefs(owner);
        if (!key.isBlank() && sources.multiplayer() && !prefs.keyNoticeShown()) {
            prefs.setKeyNoticeShown();
            return true;
        }
        return false;
    }

    /**
     * 权限:每位玩家只摸得到自己那一份,不看是不是管理员。规则写错了,整表后面多一行 {@code {"mistake": {"code", "args"}}},
     * 界面照码翻成主人的语言(和命令同一套 {@code numen.command.rule_mistake.<码>});原因的英文原话照常在 {@code problem} 里。
     */
    private Done permission(UUID owner, String op, JsonObject body) {
        if (LIST.equals(op)) {
            return Done.ok();
        }
        PermissionLibrary.Outcome outcome = PermissionLibrary.write(sources.permission(owner), op, body);
        if (outcome.mistake() == null) {
            return Done.problem(outcome.problem());
        }
        JsonObject mistake = new JsonObject();
        mistake.addProperty("code", outcome.mistake().code());
        JsonArray args = new JsonArray();
        outcome.mistake().args().forEach(args::add);
        mistake.add("args", args);
        JsonObject extra = new JsonObject();
        extra.add("mistake", mistake);
        return new Done(outcome.problem(), false, extra);
    }

    /** 外接大脑宿主的整份状态:配置、谁连着、起服失败的原因。令牌不在这里:它们是主人在游戏里用命令发的。 */
    private static JsonObject mcpServerView() {
        McpMode mode = McpMode.instance();
        var cfg = mode.config();
        JsonObject o = new JsonObject();
        o.addProperty("enabled", mode.enabled());
        o.addProperty("host", cfg.host());
        o.addProperty("port", cfg.port());
        o.addProperty("lanExposed", cfg.lanExposed());
        o.addProperty("callTimeoutSeconds", cfg.callTimeoutSeconds());
        JsonArray hidden = new JsonArray();
        cfg.hiddenTools().forEach(hidden::add);
        o.add("hiddenTools", hidden);
        o.addProperty("endpoint", mode.endpoint());
        if (mode.clientName() != null) {
            o.addProperty("clientName", mode.clientName());
        }
        o.addProperty("lastActivityMs", mode.lastActivityMs());
        if (mode.lastError() != null) {
            o.addProperty("lastError", mode.lastError());
        }
        return o;
    }

    /** 每位主人自己的偏好;只认认得的字段。 */
    private String prefs(UUID owner, String op, JsonObject body) {
        switch (op) {
            case LIST:
                return null;
            case PUT:
                if (body.has("initiative")) {
                    sources.prefs(owner).setInitiative(body.get("initiative").getAsInt());
                }
                return null;
            default:
                return "unknown operation";
        }
    }

    private String serverConfig(boolean admin, String op, JsonObject body) {
        if (!admin) {
            return "only a server admin may change the server config";
        }
        if (PUT.equals(op)) {
            String proxy = string(body, "proxy");
            String prompt = string(body, "systemPrompt");
            sources.config().set(proxy == null ? sources.config().proxy() : proxy, prompt == null ? sources.config().systemPrompt() : prompt);
            return null;
        }
        return LIST.equals(op) ? null : "unknown operation";
    }

    /**
     * 外接大脑宿主:开关、端点与超时、不暴露的工具,哪些字段在 {@code put} 里就改哪些。起服失败的原因当作办不成的原因交回。
     */
    private String mcpServer(boolean admin, String op, JsonObject body) {
        if (LIST.equals(op)) {
            return null;
        }
        if (!admin) {
            return "only a server admin may change the external brain";
        }
        if (!PUT.equals(op)) {
            return "unknown operation";
        }
        McpMode mode = McpMode.instance();
        var cfg = mode.config();
        if (body.has("host") || body.has("port") || body.has("callTimeoutSeconds") || body.has("hiddenTools")) {
            List<String> hidden = new ArrayList<>(cfg.hiddenTools());
            if (body.get("hiddenTools") instanceof JsonArray names) {
                hidden.clear();
                names.forEach(n -> hidden.add(n.getAsString()));
            }
            String host = string(body, "host") == null ? cfg.host() : string(body, "host");
            int port = body.has("port") ? body.get("port").getAsInt() : cfg.port();
            int timeout = body.has("callTimeoutSeconds") ? body.get("callTimeoutSeconds").getAsInt()
                    : cfg.callTimeoutSeconds();
            if (!mode.applySettings(host, port, Math.max(1, timeout), hidden)) {
                return mode.lastError();
            }
        }
        if (body.has("enabled") && !mode.setEnabled(body.get("enabled").getAsBoolean())) {
            return mode.lastError();
        }
        return null;
    }

    // ---- 插件 ----

    /**
     * 插件花名册的行,人人看得到同样的内容:谁、哪个版本、联动谁、此刻的状态、加了什么,以及下次启动它开还是关
     * ({@code disabledNext})、和这一次运行不一样没有({@code pending})。{@code toggle} 是这位玩家能不能改它的开关。
     */
    private void pluginRows(boolean mayToggle, JsonArray out) {
        for (PluginRoster.Plugin plugin : sources.plugins().plugins()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", plugin.id());
            row.addProperty("name", plugin.name());
            row.addProperty("version", plugin.version());
            JsonArray authors = new JsonArray();
            plugin.authors().forEach(authors::add);
            row.add("authors", authors);
            row.addProperty("target", plugin.target());
            // 目标模组只在客户端的联动:这一行装没装,看看的人自己的客户端(见 ClientMods)
            row.addProperty("clientMod", com.dwinovo.numen.api.plugin.ClientMods.isClientMod(plugin.id()));
            row.addProperty("source", plugin.source().name().toLowerCase(java.util.Locale.ROOT));
            row.addProperty("status", plugin.status().name().toLowerCase(java.util.Locale.ROOT));
            row.addProperty("reason", plugin.reason());
            row.addProperty("disabledNext", plugin.disabledNext());
            row.addProperty("pending", plugin.pending());
            row.addProperty("toggle", mayToggle);
            JsonObject adds = new JsonObject();
            plugin.adds().forEach((kind, n) -> adds.addProperty(kind.name().toLowerCase(java.util.Locale.ROOT), n));
            row.add("adds", adds);
            out.add(row);
        }
    }

    /** 插件的开关:改的是下次启动的停用名单,管理员与房主才能改,别人只读。 */
    private String plugin(boolean mayToggle, String op, JsonObject body) {
        if (LIST.equals(op)) {
            return null;
        }
        if (!ENABLE.equals(op) && !DISABLE.equals(op)) {
            return "unknown operation";
        }
        if (!mayToggle) {
            return "only a server admin or the host may switch plugins";
        }
        return sources.plugins().setDisabled(string(body, "id"), DISABLE.equals(op));
    }

    // ---- 接进来的 MCP 服务器 ----

    /**
     * 接进来的 MCP 服务器的行:内容库共有的几项,加连接状态。管理员看到服务器提供的那几台的全貌(地址、命令行、请求头、授权网址);别人
     * 只有名字、连没连上、有几个工具;自己的那几台是完整的。{@code connect} 是条目主人的"连不连"(写在条目里),
     * 和 {@code enabled}(玩家自己的开关,只管他的同伴看不看得到这台的工具)是两回事。
     */
    private void mcpRows(UUID owner, boolean admin, JsonArray out) {
        rows(sources.library(McpClientConfig.KIND, owner), out, (entry, row) -> {
            McpServerSpec spec;
            try {
                spec = McpServerSpec.read(entry.id().name(), entry.text());
            } catch (RuntimeException unreadable) {
                row.addProperty("unreadable", true);
                return;
            }
            McpConnections manager = entry.group() == Library.Group.SERVER ? sources.sharedMcp() : sources.ownerMcp(owner);
            McpConnections.ServerHandle handle = manager == null ? null : manager.handle(entry.id().toString());
            McpConnections.Status status = handle == null ? McpConnections.Status.DISABLED : handle.status();
            row.addProperty("type", spec.type());
            row.addProperty("connect", spec.enabled());
            row.addProperty("status", status.name());
            row.addProperty("connected", status == McpConnections.Status.CONNECTED);
            row.addProperty("toolCount", handle == null ? 0 : handle.toolCount());
            if (admin || entry.group() == Library.Group.MINE) {
                row.add("spec", spec.toJson());
                row.addProperty("error", handle == null ? "" : handle.error());
                if (handle != null && handle.authUrl() != null) {
                    row.addProperty("authUrl", handle.authUrl());
                }
                JsonArray tools = new JsonArray();
                if (handle != null) {
                    handle.toolNames().forEach(tools::add);
                }
                row.add("tools", tools);
            }
        });
    }

    /**
     * 接进来的 MCP 服务器的操作:内容库那一套,外加条目主人的连上与断开({@link #CONNECT}、{@link #DISCONNECT},服务器提供的只有管理员)
     * 和交回授权回调网址({@link #AUTH})。stdio 类起的是服务器机器上的进程,只有管理员能加。库变了连接跟着变。
     */
    private Done mcpClient(UUID owner, boolean admin, String op, JsonObject body) {
        if (AUTH.equals(op)) {
            return Done.problem(McpOAuth.complete(string(body, "url")));
        }
        Done done;
        if (CONNECT.equals(op) || DISCONNECT.equals(op)) {
            done = connect(owner, admin, CONNECT.equals(op), body);
        } else if (COPY.equals(op) && !admin && isStdio(sources.library(McpClientConfig.KIND, owner).server()
                .get(EntryId.parse(string(body, "id"))))) {
            // 复制过来就是他名下的一台 stdio,等于让服务器替他起进程
            done = Done.problem("only a server admin may add a stdio MCP server");
        } else {
            done = contentLibrary(owner, admin, McpClientConfig.KIND, op, body, b -> {
                try {
                    McpServerSpec spec = McpServerSpec.parse(string(b, "name"), b);
                    if (spec.isStdio() && !admin) {
                        throw new IllegalArgumentException("only a server admin may add a stdio MCP server");
                    }
                    return spec.write();
                } catch (IllegalArgumentException refused) {
                    throw refused;
                } catch (RuntimeException unreadable) {
                    throw new IllegalArgumentException("unreadable MCP server");
                }
            }, (from, to) -> { });
        }
        if (!LIST.equals(op)) {
            sources.reconcileMcp(owner);
        }
        return done;
    }

    /** 这一条是不是 stdio 类的;读不通的当不是(它连不起来)。 */
    private static boolean isStdio(Shelf.Item item) {
        if (item == null) {
            return false;
        }
        try {
            return McpServerSpec.read(item.id().name(), item.text()).isStdio();
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private Done connect(UUID owner, boolean admin, boolean on, JsonObject body) {
        boolean onServer = GROUP_SERVER.equals(string(body, "group"));
        if (onServer && !admin) {
            return Done.problem("only a server admin may connect or disconnect the server's servers");
        }
        Library library = sources.library(McpClientConfig.KIND, owner);
        Shelf shelf = onServer ? library.server() : library.mine();
        EntryId id = EntryId.parse(string(body, "id"));
        Shelf.Item item = id == null ? null : shelf.get(id);
        if (item == null) {
            return Done.problem("no such entry");
        }
        McpServerSpec spec;
        try {
            spec = McpServerSpec.read(id.name(), item.text());
        } catch (RuntimeException unreadable) {
            return Done.problem("unreadable MCP server");
        }
        shelf.write(id, spec.withEnabled(on).write());
        return Done.of(null, shelf == library.server());
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
