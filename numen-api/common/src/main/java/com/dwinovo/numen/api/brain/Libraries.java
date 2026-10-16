package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.llm.ConnectionProfile;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.UUID;
import java.util.function.Function;

/**
 * 服务器上的库对主人客户端的读写口:列、存、删,每种库({@link #PERSONA}、{@link #PROVIDER}、{@link #SERVER_CONFIG})
 * 自己说谁能做什么——判据只在这一处,界面和工具里不再另写:
 * <ul>
 *   <li><b>人设</b>:每位主人自己的库,自己读写;</li>
 *   <li><b>模型档案</b>:key 花服主的钱,只有管理员({@code Authority})能存、能删、能看全貌;别人只看得到
 *       "有哪几个、叫什么、哪个型号"——key、接口地址、代理在服务端编码的这一处就没有,客户端拿不到,也就不存在泄露;</li>
 *   <li><b>服务器配置</b>(代理、默认人设):只有管理员。</li>
 * </ul>
 * 答复永远是这位玩家此刻看得到的那一份整表,存和删之后也是;办不成附上原因。
 *
 * <p>线程:服务端主线程。
 */
public final class Libraries {

    public static final String PERSONA = "persona";
    public static final String PROVIDER = "provider";
    public static final String SERVER_CONFIG = "server-config";

    public static final String LIST = "list";
    public static final String PUT = "put";
    public static final String DELETE = "delete";

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
     */
    public record Result(String entries, String problem) {}

    /** 这台服务器上的那一份:人设按主人、模型档案是服主的、配置是加载器自己的。 */
    public static Libraries of(net.minecraft.server.MinecraftServer server) {
        return new Libraries(owner -> PersonaLibraries.of(server, owner), ProviderLibrary.instance(), new ServerConfig() {
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
    private final ProviderLibrary providers;
    private final ServerConfig config;

    public Libraries(Function<UUID, PersonaLibrary> personas, ProviderLibrary providers, ServerConfig config) {
        this.personas = personas;
        this.providers = providers;
        this.config = config;
    }

    /**
     * @param owner 发请求的玩家
     * @param admin 他是不是管理员({@code Authority.isAdmin})
     * @param body  存和删带的 JSON;列不用
     */
    public Result handle(UUID owner, boolean admin, String kind, String op, JsonObject body) {
        String problem = switch (kind) {
            case PERSONA -> persona(owner, op, body);
            case PROVIDER -> provider(admin, op, body);
            case SERVER_CONFIG -> serverConfig(admin, op, body);
            default -> "unknown library";
        };
        return new Result(view(owner, admin, kind), problem);
    }

    /** 这位玩家此刻看得到的整表。看不到的库是空表。 */
    public String view(UUID owner, boolean admin, String kind) {
        JsonArray out = new JsonArray();
        switch (kind) {
            case PERSONA -> personas.apply(owner).list().forEach(p -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", p.id());
                o.addProperty("name", p.name());
                o.addProperty("text", p.text());
                out.add(o);
            });
            case PROVIDER -> providers.list().forEach(e -> out.add(admin ? providers.toJson(e) : masked(e)));
            case SERVER_CONFIG -> {
                if (admin) {
                    JsonObject o = new JsonObject();
                    o.addProperty("proxy", config.proxy());
                    o.addProperty("systemPrompt", config.systemPrompt());
                    out.add(o);
                }
            }
            default -> { }
        }
        return out.toString();
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
            case PUT: {
                String name = string(body, "name");
                String text = string(body, "text");
                if (name == null || text == null) {
                    return "a persona needs a name and a text";
                }
                String id = string(body, "id");
                if (id != null && library.get(id) != null) {
                    library.update(id, name, text);
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
                    entry = providers.fromJson(body);
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

    private static String string(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
