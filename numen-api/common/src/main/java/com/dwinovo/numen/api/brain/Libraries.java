package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.llm.ConnectionProfile;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.mcp.client.McpClientManager;
import com.dwinovo.numen.api.mcp.client.McpOAuth;
import com.dwinovo.numen.api.mcp.server.McpConfig;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 服务器上的库对主人客户端的读写口:列、存、删,每种库({@link #PERSONA}、{@link #PROVIDER}、{@link #SERVER_CONFIG})
 * 自己说谁能做什么——判据只在这一处,界面和工具里不再另写:
 * <ul>
 *   <li><b>人设</b>:每位主人自己的库,自己读写;</li>
 *   <li><b>模型档案</b>:key 花服主的钱,只有管理员({@code Authority})能存、能删、能看全貌;别人只看得到
 *       "有哪几个、叫什么、哪个型号"——key、接口地址、代理在服务端编码的这一处就没有,客户端拿不到,也就不存在泄露;</li>
 *   <li><b>型号目录</b>({@link #CATALOG})与<b>连通性检测</b>({@link #PROVIDER_CHECK}):管理员编辑档案的表单问的,
 *       目录、草稿解析成的端点、检测的那次请求都在服务端算({@link ProviderForm}),界面只画答案;</li>
 *   <li><b>服务器配置</b>(代理、默认人设):只有管理员。</li>
 *   <li><b>偏好</b>({@link #PREFS}):每位主人自己的,自己读写;</li>
 *   <li><b>她的札记</b>({@link #NOTES}):只读,主人名下各只的札记索引(名字、一句话、类型、哪天);全文要她自己 recall;</li>
 *   <li><b>技能</b>({@link #SKILL}):服务器上有哪些,人人看得到;开关与重扫只有管理员能做(技能目录是服务器全局的);</li>
 *   <li><b>外接大脑</b>({@link #MCP_SERVER}):接入地址、令牌、局域网、开关都是管理员的事,别人看到的是空表;</li>
 *   <li><b>接进来的 MCP 服务器</b>({@link #MCP_CLIENT}):地址、令牌、命令行都是管理员的事(stdio 起的是服务器机器上的进程),
 *       别人只看得到名字、连没连上、有几个工具。授权网址只给管理员,授权回来的网址也由管理员贴回({@link #AUTH})。</li>
 * </ul>
 * 答复永远是这位玩家此刻看得到的那一份整表,存和删之后也是;办不成附上原因。
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

    public static final String LIST = "list";
    public static final String PUT = "put";
    public static final String DELETE = "delete";
    /** 人设库、技能库专有:重扫目录。 */
    public static final String RELOAD = "reload";
    /** MCP 服务器专有:接上、断开、交回授权回调网址。 */
    public static final String ENABLE = "enable";
    public static final String DISABLE = "disable";
    public static final String AUTH = "auth";
    /** 连通性检测专有:试连一份草稿。 */
    public static final String RUN = "run";

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
     * @param later   还要等一会儿才有的结局(连通性检测的那次请求):到了再作为同一种库的又一张表送出去;没有是 {@code null}
     */
    public record Result(String entries, String problem, java.util.concurrent.CompletableFuture<String> later) {

        public Result(String entries, String problem) {
            this(entries, problem, null);
        }
    }

    /** 这种库变了要不要告诉别的在线玩家:人设、目录和检测是每个人各自问各自的,不共用一张表。 */
    public static boolean shared(String kind) {
        return !PERSONA.equals(kind) && !CATALOG.equals(kind) && !PROVIDER_CHECK.equals(kind);
    }

    /** 这台服务器上的那一份:人设按主人、模型档案是服主的、配置是加载器自己的。 */
    public static Libraries of(net.minecraft.server.MinecraftServer server) {
        return new Libraries(owner -> PersonaLibraries.of(server, owner), owner -> OwnerPrefs.of(server, owner),
                owner -> CompanionRegistry.get(server).ownedBy(owner).stream().map(java.util.Map.Entry::getKey).toList(),
                ProviderLibrary.instance(), new ServerConfig() {
            private final com.dwinovo.numen.api.platform.services.INumenConfig config =
                    com.dwinovo.numen.api.platform.Services.CONFIG;

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
        });
    }

    private final Function<UUID, PersonaLibrary> personas;
    private final Function<UUID, OwnerPrefs> prefs;
    private final Function<UUID, List<UUID>> companions;
    private final ProviderLibrary providers;
    private final ServerConfig config;

    public Libraries(Function<UUID, PersonaLibrary> personas, Function<UUID, OwnerPrefs> prefs,
                     Function<UUID, List<UUID>> companions, ProviderLibrary providers, ServerConfig config) {
        this.personas = personas;
        this.prefs = prefs;
        this.companions = companions;
        this.providers = providers;
        this.config = config;
    }

    /**
     * @param owner 发请求的玩家
     * @param admin 他是不是管理员({@code Authority.isAdmin})
     * @param body  存和删带的 JSON;列不用
     */
    public Result handle(UUID owner, boolean admin, String kind, String op, JsonObject body) {
        if (PROVIDER_CHECK.equals(kind)) {
            return check(admin, op, body);
        }
        String problem = switch (kind) {
            case PERSONA -> persona(owner, op, body);
            case PROVIDER -> provider(admin, op, body);
            case SERVER_CONFIG -> serverConfig(admin, op, body);
            case MCP_CLIENT -> mcpClient(admin, op, body);
            case MCP_SERVER -> mcpServer(admin, op, body);
            case PREFS -> prefs(owner, op, body);
            case NOTES -> LIST.equals(op) ? null : "unknown operation";
            case SKILL -> skill(admin, op, body);
            case CATALOG -> catalog(admin, op);
            default -> "unknown library";
        };
        return new Result(view(owner, admin, kind, body), problem);
    }

    /** 连通性检测:管理员试连一份草稿,先答"在试",结局到了再送一张表。 */
    private Result check(boolean admin, String op, JsonObject body) {
        if (!admin) {
            return new Result("[]", "only a server admin may test a connection");
        }
        if (!RUN.equals(op)) {
            return new Result("[]", "unknown operation");
        }
        if (!body.has("draft") || !body.get("draft").isJsonObject()) {
            return new Result("[]", "a connection test needs the draft to test");
        }
        JsonArray checking = new JsonArray();
        checking.add(ProviderForm.state(ProviderForm.STATE_CHECKING));
        return new Result(checking.toString(), null, ProviderForm.check(providers, body).thenApply(state -> {
            JsonArray done = new JsonArray();
            done.add(state);
            return done.toString();
        }));
    }

    private static String catalog(boolean admin, String op) {
        if (!admin) {
            return "only a server admin may edit model profiles";
        }
        return LIST.equals(op) ? null : "unknown operation";
    }

    /** 这位玩家此刻看得到的整表。看不到的库是空表。 */
    public String view(UUID owner, boolean admin, String kind) {
        return view(owner, admin, kind, new JsonObject());
    }

    /** 同上;目录这种按问题答的库,{@code ask} 是问题(见 {@link ProviderForm#answer})。 */
    private String view(UUID owner, boolean admin, String kind, JsonObject ask) {
        JsonArray out = new JsonArray();
        switch (kind) {
            case PERSONA -> personas.apply(owner).list().forEach(p -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", p.id());
                o.addProperty("name", p.name());
                o.addProperty("text", p.text());
                out.add(o);
            });
            case PROVIDER -> providers.list().forEach(e -> out.add(admin ? ProviderLibrary.entryToJson(e) : masked(e)));
            case SERVER_CONFIG -> {
                if (admin) {
                    JsonObject o = new JsonObject();
                    o.addProperty("proxy", config.proxy());
                    o.addProperty("systemPrompt", config.systemPrompt());
                    out.add(o);
                }
            }
            case MCP_CLIENT -> McpClientManager.servers().forEach(h -> out.add(mcpView(h, admin)));
            case MCP_SERVER -> {
                if (admin) {
                    out.add(mcpServerView());
                }
            }
            case CATALOG -> {
                if (admin) {
                    out.add(ProviderForm.answer(providers, ask));
                }
            }
            case PREFS -> out.add(prefs.apply(owner).toJson());
            case NOTES -> companions.apply(owner).forEach(companion -> NoteBook.of(companion).index().forEach(note -> {
                JsonObject o = new JsonObject();
                o.addProperty("companion", companion.toString());
                o.addProperty("name", note.name());
                o.addProperty("description", note.description());
                o.addProperty("type", note.type());
                o.addProperty("day", note.day());
                out.add(o);
            }));
            case SKILL -> SkillRegistry.instance().all().forEach(info -> {
                JsonObject o = new JsonObject();
                o.addProperty("name", info.name());
                o.addProperty("description", info.description());
                o.addProperty("enabled", !SkillRegistry.instance().isDisabled(info.name()));
                out.add(o);
            });
            default -> { }
        }
        return out.toString();
    }

    /** 外接大脑宿主的整份状态:配置、令牌、谁连着、起服失败的原因。 */
    private static JsonObject mcpServerView() {
        McpMode mode = McpMode.instance();
        McpConfig cfg = mode.config();
        JsonObject o = new JsonObject();
        o.addProperty("enabled", mode.enabled());
        o.addProperty("host", cfg.host());
        o.addProperty("port", cfg.port());
        o.addProperty("lanExposed", cfg.lanExposed());
        o.addProperty("token", cfg.token());
        o.addProperty("callTimeoutSeconds", cfg.callTimeoutSeconds());
        JsonArray hidden = new JsonArray();
        cfg.hiddenTools().forEach(hidden::add);
        o.add("hiddenTools", hidden);
        o.addProperty("quietFallback", cfg.quietFallback());
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

    /** 管理员看到整份配置加连接状态;别人只有名字、连没连上、工具数。 */
    private static JsonObject mcpView(McpClientManager.ServerHandle handle, boolean admin) {
        JsonObject o = new JsonObject();
        McpClientConfig.ServerSpec spec = McpClientManager.spec(handle.name());
        if (admin && spec != null) {
            o = McpClientConfig.serverToJson(spec);
            o.addProperty("status", handle.status().name());
            o.addProperty("error", handle.error());
            if (handle.authUrl() != null) {
                o.addProperty("authUrl", handle.authUrl());
            }
            JsonArray tools = new JsonArray();
            handle.toolNames().forEach(tools::add);
            o.add("tools", tools);
        } else {
            o.addProperty("name", handle.name());
            o.addProperty("connected", handle.status() == McpClientManager.Status.CONNECTED);
        }
        o.addProperty("toolCount", handle.toolCount());
        return o;
    }

    /** 别人只看得到有哪几个、叫什么、哪个型号:选档案给同伴用,不需要知道 key。 */
    private static JsonObject masked(ProviderLibrary.Entry entry) {
        JsonObject o = new JsonObject();
        o.addProperty("id", entry.id());
        o.addProperty("name", entry.name());
        ConnectionProfile p = entry.profile();
        if (p.provider() != null) {
            o.addProperty("provider", p.provider());
        }
        if (p.model() != null) {
            o.addProperty("model", p.model());
        }
        return o;
    }

    // ---- 各库的读写 ----

    private String persona(UUID owner, String op, JsonObject body) {
        PersonaLibrary library = personas.apply(owner);
        switch (op) {
            case LIST:
                return null;
            case RELOAD:
                library.reload();   // 目录里的 .md 是真源,外部编辑器改过就重扫
                return null;
            case PUT: {
                String name = string(body, "name");
                String text = string(body, "text");
                if (name == null || text == null) {
                    return "a persona needs a name and a text";
                }
                String id = string(body, "id");
                if (id != null && library.get(id) != null) {
                    PersonaLibrary.Persona saved = library.update(id, name, text);
                    // 人设的 id 就是文件名:改名等于换了身份,在用它的同伴得重新指过去
                    if (saved != null && !saved.id().equals(id)) {
                        CompanionLoops.repersona(owner, id, saved.id());
                    }
                } else {
                    library.create(name, text);
                }
                return null;
            }
            case DELETE: {
                String id = string(body, "id");
                if (id == null || library.get(id) == null) {
                    return "no such persona";
                }
                library.remove(id);
                return null;
            }
            default:
                return "unknown operation";
        }
    }

    private String provider(boolean admin, String op, JsonObject body) {
        if (LIST.equals(op)) {
            return null;
        }
        if (!admin) {
            return "only a server admin may change model profiles";
        }
        switch (op) {
            case PUT: {
                ProviderLibrary.Entry entry;
                try {
                    entry = ProviderLibrary.entryFromJson(body);
                } catch (RuntimeException unreadable) {
                    return "unreadable profile";
                }
                if (entry.name() == null || entry.name().isBlank()) {
                    return "a profile needs a name";
                }
                if (entry.id() != null && providers.get(entry.id()) != null) {
                    providers.update(entry);
                } else {
                    providers.create(entry.name(), entry.profile());
                }
                return null;
            }
            case DELETE: {
                String id = string(body, "id");
                if (id == null || providers.get(id) == null) {
                    return "no such profile";
                }
                providers.remove(id);
                return null;
            }
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
            config.set(proxy == null ? config.proxy() : proxy, prompt == null ? config.systemPrompt() : prompt);
            return null;
        }
        return LIST.equals(op) ? null : "unknown operation";
    }

    /**
     * 外接大脑宿主:开关、端点与超时、不暴露的工具、令牌、失联回退,哪些字段在 {@code put} 里就改哪些。起服失败的原因当作办不成的原因交回。
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
        McpConfig cfg = mode.config();
        if (body.has("host") || body.has("port") || body.has("callTimeoutSeconds") || body.has("hiddenTools")
                || body.has("token") || body.has("regenerateToken")) {
            List<String> hidden = new ArrayList<>(cfg.hiddenTools());
            if (body.get("hiddenTools") instanceof JsonArray names) {
                hidden.clear();
                names.forEach(n -> hidden.add(n.getAsString()));
            }
            String token = body.has("regenerateToken") ? McpConfig.mintToken()
                    : body.has("token") ? string(body, "token") : cfg.token();
            String host = string(body, "host") == null ? cfg.host() : string(body, "host");
            int port = body.has("port") ? body.get("port").getAsInt() : cfg.port();
            int timeout = body.has("callTimeoutSeconds") ? body.get("callTimeoutSeconds").getAsInt()
                    : cfg.callTimeoutSeconds();
            if (!mode.applySettings(host, port, Math.max(1, timeout), hidden, token == null ? "" : token)) {
                return mode.lastError();
            }
        }
        if (body.has("enabled") && !mode.setEnabled(body.get("enabled").getAsBoolean())) {
            return mode.lastError();
        }
        if (body.has("quietFallback")) {
            mode.setQuietFallback(body.get("quietFallback").getAsBoolean());
        }
        return null;
    }

    /** 每位主人自己的偏好;只认认得的字段。 */
    private String prefs(UUID owner, String op, JsonObject body) {
        switch (op) {
            case LIST:
                return null;
            case PUT:
                if (body.has("initiative")) {
                    prefs.apply(owner).setInitiative(body.get("initiative").getAsInt());
                }
                return null;
            default:
                return "unknown operation";
        }
    }

    /** 技能目录是服务器全局的:谁都能看有哪些,开关只有管理员能拨。 */
    private String skill(boolean admin, String op, JsonObject body) {
        if (LIST.equals(op)) {
            return null;
        }
        if (!admin) {
            return "only a server admin may switch skills";
        }
        if (RELOAD.equals(op)) {
            SkillRegistry.instance().rescan();   // 技能目录是服务器全局的,管理员往里放了新的,重扫才看得到
            return null;
        }
        String name = string(body, "name");
        if (name == null || SkillRegistry.instance().find(name).isEmpty()) {
            return "no such skill";
        }
        switch (op) {
            case ENABLE:
                SkillRegistry.instance().setEnabled(name, true);
                return null;
            case DISABLE:
                SkillRegistry.instance().setEnabled(name, false);
                return null;
            default:
                return "unknown operation";
        }
    }

    private String mcpClient(boolean admin, String op, JsonObject body) {
        if (LIST.equals(op)) {
            return null;
        }
        if (!admin) {
            return "only a server admin may change connected MCP servers";
        }
        String name = string(body, "name");
        switch (op) {
            case PUT: {
                McpClientConfig.ServerSpec spec;
                try {
                    spec = McpClientConfig.parseServer(body);
                } catch (RuntimeException unreadable) {
                    return "unreadable MCP server";
                }
                if (name == null || name.isBlank()) {
                    return "an MCP server needs a name";
                }
                McpClientManager.upsertServer(spec);
                String renamedFrom = string(body, "replaces");
                if (renamedFrom != null && !renamedFrom.equals(name)) {
                    McpClientManager.deleteServer(renamedFrom);
                }
                return null;
            }
            case DELETE:
            case ENABLE:
            case DISABLE: {
                if (name == null || McpClientManager.spec(name) == null) {
                    return "no such MCP server";
                }
                switch (op) {
                    case DELETE -> McpClientManager.deleteServer(name);
                    case ENABLE -> McpClientManager.enableServer(name);
                    default -> McpClientManager.disableServer(name);
                }
                return null;
            }
            case AUTH:
                return McpOAuth.complete(string(body, "url"));
            default:
                return "unknown operation";
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
