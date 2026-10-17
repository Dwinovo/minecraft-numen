package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.provider.ThinkingPlan;
import com.dwinovo.numen.api.brain.CompanionState;
import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.mcp.client.McpClientManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器上那几种库在界面眼里的样子:{@link RemoteLibrary} 里最近一次读到的整表,读成界面直接用的小记录。真源在服务器——
 * 这里不存、不缓存,每次现读;存和删经 {@link RemoteCompanions#library()} 发给服务端,谁能做什么由它判。
 * 看不到的东西(普通玩家看不到的 key、地址、令牌)服务端编码时就没有,这里读不出来也就谈不上泄露。
 */
public final class ServerLibraries {

    private ServerLibraries() {}

    private static JsonArray entries(String kind) {
        return RemoteCompanions.library().entries(kind);
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    // ---- 人设 ----

    public record Persona(String id, String name, String text) {}

    public static List<Persona> personas() {
        List<Persona> out = new ArrayList<>();
        for (JsonElement e : entries(Libraries.PERSONA)) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Persona(text(o, "id"), text(o, "name"), text(o, "text") == null ? "" : text(o, "text")));
        }
        return out;
    }

    public static Persona persona(String id) {
        for (Persona p : personas()) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    // ---- 模型档案 ----

    /** {@code full} 只有管理员有(含 key、地址);别人只有 id、名字、厂家、型号。 */
    public record Provider(String id, String name, String provider, String model, ProviderLibrary.Entry full) {

        public boolean hasKey() {
            return full != null && full.profile().apiKey() != null && !full.profile().apiKey().isBlank();
        }
    }

    public static List<Provider> providers() {
        List<Provider> out = new ArrayList<>();
        boolean admin = RemoteCompanions.admin();
        for (JsonElement e : entries(Libraries.PROVIDER)) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Provider(text(o, "id"), text(o, "name"), text(o, "provider"), text(o, "model"),
                    admin ? ProviderLibrary.entryFromJson(o) : null));
        }
        return out;
    }

    // ---- 型号目录与连通性检测(管理员的档案表单问的;目录、端点解析、检测都在服务端算) ----

    /** 一个厂家。{@code defaultModel}:换到它时先选的型号。 */
    public record Vendor(String id, String name, String host, boolean popular, String defaultModel) {}

    /** 一个型号。 */
    public record CatalogModel(String id, String name, int contextWindow, int maxOutput, List<String> inputs,
                               boolean toolCall, boolean reasons, String releaseDate) {}

    /**
     * 一份草稿在服务端解析出的端点(不含地址覆盖)。
     *
     * @param steps     可选的思考档位与每档实际会发什么,第一个永远是自动
     * @param effective 草稿选的档位在这个计划里生效的那一档
     */
    public record Preview(String baseUrl, int contextWindow, List<ThinkingPlan.Step> steps, ThinkingLevel effective) {

        /** 只有自动:这个型号在这个端点上没有可控的思考。 */
        public boolean autoOnly() {
            return steps.size() == 1;
        }
    }

    /**
     * 目录问答的最近一次答案。{@code models} 只在问时要了型号才有({@code modelsFor} 是它们属于哪个厂家)、{@code preview}
     * 只在问时带了草稿才有,否则是 {@code null}。
     */
    public record CatalogAnswer(List<Vendor> vendors, List<CatalogModel> models, String modelsFor, Preview preview) {

        public static final CatalogAnswer EMPTY = new CatalogAnswer(List.of(), null, "", null);
    }

    /** 服务端对目录问题的最近一次答案;还没答过是 {@link CatalogAnswer#EMPTY}。 */
    public static CatalogAnswer catalog() {
        JsonArray answers = entries(Libraries.CATALOG);
        return answers.isEmpty() ? CatalogAnswer.EMPTY : catalogOf(answers.get(0).getAsJsonObject());
    }

    /** 服务端写的一份目录答案读成记录(编解码只这一对:{@code ProviderForm#answer} 与这里)。 */
    public static CatalogAnswer catalogOf(JsonObject o) {
        List<Vendor> vendors = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("vendors")) {
            JsonObject v = e.getAsJsonObject();
            vendors.add(new Vendor(text(v, "id"), text(v, "name"), text(v, "host"), v.get("popular").getAsBoolean(),
                    text(v, "defaultModel")));
        }
        List<CatalogModel> models = null;
        if (o.has("models")) {
            models = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("models")) {
                JsonObject m = e.getAsJsonObject();
                List<String> inputs = new ArrayList<>();
                m.getAsJsonArray("inputs").forEach(i -> inputs.add(i.getAsString()));
                models.add(new CatalogModel(text(m, "id"), text(m, "name"), m.get("contextWindow").getAsInt(),
                        m.get("maxOutput").getAsInt(), inputs, m.get("toolCall").getAsBoolean(),
                        m.get("reasons").getAsBoolean(), text(m, "releaseDate")));
            }
        }
        Preview preview = null;
        if (o.has("preview")) {
            JsonObject p = o.getAsJsonObject("preview");
            List<ThinkingPlan.Step> steps = new ArrayList<>();
            for (JsonElement e : p.getAsJsonArray("steps")) {
                JsonObject s = e.getAsJsonObject();
                steps.add(new ThinkingPlan.Step(ThinkingLevel.byId(text(s, "level")), new ThinkingPlan.Wire(
                        ThinkingPlan.Wire.Kind.valueOf(text(s, "wire")), text(s, "effort"), s.get("tokens").getAsInt())));
            }
            preview = new Preview(text(p, "baseUrl"), p.get("contextWindow").getAsInt(), List.copyOf(steps),
                    ThinkingLevel.byId(text(p, "effective")));
        }
        return new CatalogAnswer(List.copyOf(vendors), models == null ? null : List.copyOf(models),
                text(o, "modelsFor") == null ? "" : text(o, "modelsFor"), preview);
    }

    /**
     * 连通性检测的结局。{@code state}:{@code checking} 在试、{@code ok}、{@code need_key}、{@code need_url}、{@code failed}
     * (这时 {@code cause} 说为什么)。
     */
    public record Check(String state, Cause cause) {

        public boolean done() {
            return !"checking".equals(state);
        }
    }

    /** 最近一次检测的结局;没试过是 {@code null}。 */
    public static Check check() {
        JsonArray results = entries(Libraries.PROVIDER_CHECK);
        if (results.isEmpty()) {
            return null;
        }
        JsonObject o = results.get(0).getAsJsonObject();
        return new Check(text(o, "state"), o.has("cause") ? CompanionState.cause(o.getAsJsonObject("cause")) : null);
    }

    // ---- 她的札记 ----

    /** 一条札记的索引行(不含全文)。 */
    public record Note(String name, String description, String type, int day) {}

    /** 这只同伴的札记索引。 */
    public static List<Note> notes(java.util.UUID companion) {
        List<Note> out = new ArrayList<>();
        for (JsonElement e : entries(Libraries.NOTES)) {
            JsonObject o = e.getAsJsonObject();
            if (companion.toString().equals(text(o, "companion"))) {
                out.add(new Note(text(o, "name"), text(o, "description"), text(o, "type"), o.get("day").getAsInt()));
            }
        }
        return out;
    }

    // ---- 技能 ----

    public record Skill(String name, String description, boolean enabled) {}

    public static List<Skill> skills() {
        List<Skill> out = new ArrayList<>();
        for (JsonElement e : entries(Libraries.SKILL)) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Skill(text(o, "name"), text(o, "description"), !o.has("enabled") || o.get("enabled").getAsBoolean()));
        }
        return out;
    }

    // ---- 偏好 ----

    /** 主动性档位;服务端还没说过是默认档。 */
    public static int initiative() {
        JsonArray prefs = entries(Libraries.PREFS);
        if (prefs.isEmpty()) {
            return com.dwinovo.numen.api.agent.inbox.EventQueue.DEFAULT_LEVEL;
        }
        JsonObject o = prefs.get(0).getAsJsonObject();
        return o.has("initiative") ? o.get("initiative").getAsInt()
                : com.dwinovo.numen.api.agent.inbox.EventQueue.DEFAULT_LEVEL;
    }

    public static void setInitiative(int level) {
        JsonObject body = new JsonObject();
        body.addProperty("initiative", level);
        RemoteCompanions.library().put(Libraries.PREFS, body);
    }

    // ---- 接进来的 MCP 服务器 ----

    /**
     * 一台接进来的 MCP 服务器。管理员有 {@code spec}(地址、命令行、请求头)、状态、工具名、授权网址;别人只有名字、连没连上、工具数。
     */
    public record McpClientRow(String name, boolean connected, int toolCount, McpClientConfig.ServerSpec spec,
                               McpClientManager.Status status, List<String> tools, String error, String authUrl) {

        public boolean admin() {
            return spec != null;
        }
    }

    public static List<McpClientRow> mcpClients() {
        List<McpClientRow> out = new ArrayList<>();
        for (JsonElement e : entries(Libraries.MCP_CLIENT)) {
            JsonObject o = e.getAsJsonObject();
            int count = o.has("toolCount") ? o.get("toolCount").getAsInt() : 0;
            if (!o.has("status")) {
                out.add(new McpClientRow(text(o, "name"), o.has("connected") && o.get("connected").getAsBoolean(), count,
                        null, null, List.of(), "", null));
                continue;
            }
            McpClientManager.Status status = McpClientManager.Status.valueOf(text(o, "status"));
            List<String> tools = new ArrayList<>();
            if (o.has("tools")) {
                o.getAsJsonArray("tools").forEach(t -> tools.add(t.getAsString()));
            }
            out.add(new McpClientRow(text(o, "name"), status == McpClientManager.Status.CONNECTED, count,
                    McpClientConfig.parseServer(o), status, tools, text(o, "error") == null ? "" : text(o, "error"),
                    text(o, "authUrl")));
        }
        return out;
    }

    // ---- 外接大脑宿主 ----

    /** 管理员看到的宿主整份状态;别人读到的表是空的,{@link #mcpServer} 给 {@code null}。 */
    public record McpServerState(boolean enabled, String host, int port, boolean lanExposed, String token,
                                 int callTimeoutSeconds, List<String> hiddenTools, boolean quietFallback,
                                 String endpoint, String clientName, long lastActivityMs, String lastError) {

        /** 要不要重搭界面看这个:不含谁连着、多久前活跃、起服失败这些随时在变的状态。 */
        public boolean sameConfig(McpServerState other) {
            return other != null && enabled == other.enabled && host.equals(other.host) && port == other.port
                    && token.equals(other.token) && callTimeoutSeconds == other.callTimeoutSeconds
                    && hiddenTools.equals(other.hiddenTools) && quietFallback == other.quietFallback;
        }

        /** 打码后的令牌,给界面显示——明文令牌不上屏。 */
        public String maskedToken() {
            if (token == null || token.isBlank()) {
                return "";
            }
            return token.length() <= 8 ? "•".repeat(token.length()) : token.substring(0, 8) + "•".repeat(6);
        }
    }

    public static McpServerState mcpServer() {
        JsonArray rows = entries(Libraries.MCP_SERVER);
        if (rows.isEmpty()) {
            return null;
        }
        JsonObject o = rows.get(0).getAsJsonObject();
        List<String> hidden = new ArrayList<>();
        o.getAsJsonArray("hiddenTools").forEach(t -> hidden.add(t.getAsString()));
        return new McpServerState(o.get("enabled").getAsBoolean(), text(o, "host"), o.get("port").getAsInt(),
                o.get("lanExposed").getAsBoolean(), text(o, "token") == null ? "" : text(o, "token"),
                o.get("callTimeoutSeconds").getAsInt(), hidden, o.get("quietFallback").getAsBoolean(),
                text(o, "endpoint"), text(o, "clientName"), o.get("lastActivityMs").getAsLong(), text(o, "lastError"));
    }
}
