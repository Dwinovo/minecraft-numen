package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.provider.ThinkingPlan;
import com.dwinovo.numen.api.brain.CompanionState;
import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.mcp.client.McpClientManager;
import com.dwinovo.numen.api.skin.SkinError;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.voice.VoiceProfile;
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

    // ---- 内容库(人设、模型档案、技能、接进来的 MCP 服务器)共有的几项 ----

    /**
     * 一行内容库条目共有的几项:内置还是我的、玩家有没有把它关掉、另一组有没有同名的、内置的和 jar 的关系
     * (改过没有、有没有新版、来源卸载了没有)。
     *
     * @param id 库里的名字({@code 命名空间:名字})
     */
    public record Base(String id, String name, boolean builtin, boolean enabled, boolean paired, boolean modified,
                       boolean update, boolean gone) {}

    /** 叠起来之后算数的:内置的被我的同名盖着时,内置那一行不算。 */
    private static boolean effective(Base b) {
        return !(b.builtin() && b.paired());
    }

    private static Base base(JsonObject o) {
        return new Base(text(o, "id"), text(o, "name"), "builtin".equals(text(o, "group")),
                !o.has("enabled") || o.get("enabled").getAsBoolean(), flag(o, "paired"), flag(o, "modified"),
                flag(o, "update"), flag(o, "gone"));
    }

    private static boolean flag(JsonObject o, String key) {
        return o.has(key) && o.get(key).getAsBoolean();
    }

    // ---- 人设 ----

    public record Persona(Base base, String text) {

        public String id() {
            return base.id();
        }

        public String name() {
            return base.name();
        }
    }

    /** 内置的在前,我的在后。 */
    public static List<Persona> personas() {
        List<Persona> out = new ArrayList<>();
        for (JsonElement e : entries(Libraries.PERSONA)) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Persona(base(o), text(o, "text") == null ? "" : text(o, "text")));
        }
        return out;
    }

    /** 选人设时能选的:叠起来之后算数的、没被玩家关掉的。 */
    public static List<Persona> personasInUse() {
        return personas().stream().filter(p -> effective(p.base()) && p.base().enabled()).toList();
    }

    public static Persona persona(String id) {
        for (Persona p : personas()) {
            if (effective(p.base()) && p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    // ---- 模型档案 ----

    /**
     * {@code full} 是档案的全部内容(含地址、代理),只有看得到它的人有:管理员看内置的、人人看自己的;
     * 别人看内置的只有厂家、型号。{@code keySource}:这位玩家用这份档案会用谁的 key——{@code mine}、{@code server},没有是空串。
     */
    public record Provider(Base base, String provider, String model, ProviderLibrary.Entry full, String keySource) {

        public String id() {
            return base.id();
        }

        public String name() {
            return base.name();
        }

        public boolean hasKey() {
            return !keySource.isEmpty();
        }
    }

    public static List<Provider> providers() {
        List<Provider> out = new ArrayList<>();
        boolean admin = RemoteCompanions.admin();
        for (JsonElement e : entries(Libraries.PROVIDER)) {
            JsonObject o = e.getAsJsonObject();
            Base base = base(o);
            boolean visible = admin || !base.builtin();
            out.add(new Provider(base, text(o, "provider"), text(o, "model"),
                    visible && !flag(o, "unreadable") ? new ProviderLibrary.Entry(base.id(), base.name(),
                            ProviderLibrary.profileFromJson(o)) : null,
                    text(o, "keySource") == null ? "" : text(o, "keySource")));
        }
        return out;
    }

    /** 选模型档案时能选的:叠起来之后算数的、没被玩家关掉的。 */
    public static List<Provider> providersInUse() {
        return providers().stream().filter(p -> effective(p.base()) && p.base().enabled()).toList();
    }

    // ---- 钥匙串 ----

    /** 一个服务商的 key:{@code mine} 是他自己的(没有是 null);{@code server} 只有管理员看得到。 */
    public record KeyRow(String service, String mine, boolean serverSet, String server) {}

    public static List<KeyRow> keys() {
        List<KeyRow> out = new ArrayList<>();
        for (JsonElement e : entries(Libraries.KEY)) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("service")) {
                out.add(new KeyRow(text(o, "service"), text(o, "mine"), flag(o, "serverSet"), text(o, "server")));
            }
        }
        return out;
    }

    /** 这个服务商他自己的 key;没存过是空串。 */
    public static String mineKey(String service) {
        return keys().stream().filter(k -> k.service().equals(service)).map(KeyRow::mine)
                .filter(java.util.Objects::nonNull).findFirst().orElse("");
    }

    /** 这个服务商服主的 key;没有或看不到是空串。 */
    public static String serverKey(String service) {
        return keys().stream().filter(k -> k.service().equals(service)).map(KeyRow::server)
                .filter(java.util.Objects::nonNull).findFirst().orElse("");
    }

    /** 答复里带的提醒({@link Libraries#NOTICE_OWNER_SEES_KEY});没有是 null。 */
    public static String keyNotice() {
        for (JsonElement e : entries(Libraries.KEY)) {
            if (e.getAsJsonObject().has("notice")) {
                return text(e.getAsJsonObject(), "notice");
            }
        }
        return null;
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

    public record Skill(Base base, String skill, String description) {

        /** 模型看到的技能名({@code SKILL.md} 里的 {@code name:})。 */
        public String name() {
            return skill;
        }

        public boolean enabled() {
            return base.enabled();
        }
    }

    /** 内置的在前,我的在后。 */
    public static List<Skill> skills() {
        List<Skill> out = new ArrayList<>();
        for (JsonElement e : entries(Libraries.SKILL)) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Skill(base(o), text(o, "skill"), text(o, "description")));
        }
        return out;
    }

    /** 叠起来之后算数的技能,含被玩家关掉的(开关面板要列出来让他再打开)。 */
    public static List<Skill> skillsEffective() {
        return skills().stream().filter(s -> effective(s.base())).toList();
    }

    // ---- 声线 ----

    /** {@code profile} 是声线的全部参数(没有 key);读不通的条目是 {@code null}。 */
    public record Voice(Base base, VoiceProfile profile) {

        public String id() {
            return base.id();
        }

        public String name() {
            return base.name();
        }
    }

    /** 内置的在前,我的在后。 */
    public static List<Voice> voices() {
        List<Voice> out = new ArrayList<>();
        for (JsonElement e : entries(Libraries.VOICE)) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Voice(base(o), flag(o, "unreadable") ? null : VoiceProfile.fromJson(o)));
        }
        return out;
    }

    /** 选声线时能选的:叠起来之后算数的、没被玩家关掉的。 */
    public static List<Voice> voicesInUse() {
        return voices().stream().filter(v -> effective(v.base()) && v.base().enabled() && v.profile() != null).toList();
    }

    /** 叫这个名字、此刻在用的声线;没有是 {@code null}。 */
    public static Voice voice(String id) {
        for (Voice v : voicesInUse()) {
            if (v.id().equals(id)) {
                return v;
            }
        }
        return null;
    }

    // ---- 皮肤 ----

    /** {@code png} 是原图;借来的正版皮肤取不到图时是空数组。读不通的条目 {@code variant} 为 {@code null}。 */
    public record Skin(Base base, String variant, boolean signed, byte[] png) {

        public String id() {
            return base.id();
        }

        public String name() {
            return base.name();
        }
    }

    /** 一张在服务端队里等签的,或刚失败的;失败的 {@code failure} 非空,排着的 {@code position} 是排第几。 */
    public record SkinJob(long job, String name, String state, int position, SkinError failure) {

        public boolean failed() {
            return failure != null;
        }
    }

    /** 内置的在前,我的在后;不含排队中的({@link #skinJobs})。 */
    public static List<Skin> skins() {
        List<Skin> out = new ArrayList<>();
        for (JsonElement e : entries(Libraries.SKIN)) {
            JsonObject o = e.getAsJsonObject();
            if (!flag(o, "pending")) {
                out.add(new Skin(base(o), flag(o, "unreadable") ? null : text(o, "variant"), flag(o, "signed"),
                        o.has("png") ? java.util.Base64.getDecoder().decode(text(o, "png")) : new byte[0]));
            }
        }
        return out;
    }

    /** 选皮肤时能选的:叠起来之后算数的、没被玩家关掉的、签过名的。 */
    public static List<Skin> skinsInUse() {
        return skins().stream().filter(s -> effective(s.base()) && s.base().enabled() && s.signed()).toList();
    }

    /** 这位玩家在服务端队里的与刚失败的。 */
    public static List<SkinJob> skinJobs() {
        List<SkinJob> out = new ArrayList<>();
        for (JsonElement e : entries(Libraries.SKIN)) {
            JsonObject o = e.getAsJsonObject();
            if (flag(o, "pending")) {
                SkinError failure = null;
                if (o.has("problem")) {
                    JsonObject p = o.getAsJsonObject("problem");
                    List<String> args = new ArrayList<>();
                    p.getAsJsonArray("args").forEach(a -> args.add(a.getAsString()));
                    failure = SkinError.of(text(p, "code"), args);
                }
                out.add(new SkinJob(o.get("job").getAsLong(), text(o, "name"), text(o, "state"),
                        o.get("position").getAsInt(), failure));
            }
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
     * 一台接进来的 MCP 服务器。{@code spec}(地址、命令行、请求头)管理员看内置的、人人看自己的,别人看内置的只有名字、连没连上、
     * 工具数。{@code connect} 是条目主人的"连不连",{@code base.enabled} 是玩家自己的开关(只管他的同伴看不看得到它的工具)。
     */
    public record McpClientRow(Base base, boolean connect, boolean connected, int toolCount,
                               McpClientConfig.ServerSpec spec, McpClientManager.Status status, List<String> tools,
                               String error, String authUrl) {

        public String id() {
            return base.id();
        }

        public String name() {
            return base.name();
        }

        /** 看得到配置的全貌。 */
        public boolean full() {
            return spec != null;
        }
    }

    public static List<McpClientRow> mcpClients() {
        List<McpClientRow> out = new ArrayList<>();
        for (JsonElement e : entries(Libraries.MCP_CLIENT)) {
            JsonObject o = e.getAsJsonObject();
            if (flag(o, "unreadable")) {
                continue;
            }
            Base base = base(o);
            int count = o.has("toolCount") ? o.get("toolCount").getAsInt() : 0;
            McpClientManager.Status status = McpClientManager.Status.valueOf(text(o, "status"));
            List<String> tools = new ArrayList<>();
            if (o.has("tools")) {
                o.getAsJsonArray("tools").forEach(t -> tools.add(t.getAsString()));
            }
            out.add(new McpClientRow(base, flag(o, "connect"), flag(o, "connected"), count,
                    o.has("spec") ? McpClientConfig.parseServer(base.name(), o.getAsJsonObject("spec")) : null,
                    status, tools, text(o, "error") == null ? "" : text(o, "error"), text(o, "authUrl")));
        }
        return out;
    }

    // ---- 外接大脑宿主 ----

    /** 管理员看到的宿主整份状态;别人读到的表是空的,{@link #mcpServer} 给 {@code null}。 */
    public record McpServerState(boolean enabled, String host, int port, boolean lanExposed,
                                 int callTimeoutSeconds, List<String> hiddenTools,
                                 String endpoint, String clientName, long lastActivityMs, String lastError) {

        /** 要不要重搭界面看这个:不含谁连着、多久前活跃、起服失败这些随时在变的状态。 */
        public boolean sameConfig(McpServerState other) {
            return other != null && enabled == other.enabled && host.equals(other.host) && port == other.port
                    && callTimeoutSeconds == other.callTimeoutSeconds && hiddenTools.equals(other.hiddenTools);
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
                o.get("lanExposed").getAsBoolean(), o.get("callTimeoutSeconds").getAsInt(), hidden,
                text(o, "endpoint"), text(o, "clientName"), o.get("lastActivityMs").getAsLong(), text(o, "lastError"));
    }

    // ---- 权限 ----

    /**
     * 规则页的一行规则。{@code layer}:{@code hard}(服主的)、{@code owner}(主人自己的,可改)、{@code factory}(随模组发的);
     * {@code table}:{@code deny}、{@code ask}、{@code allow};{@code byHer} 与 {@code note} 只有主人层有意义。
     */
    public record PermissionRule(String layer, String table, String rule, boolean byHer, String note, int count,
                                 long lastMillis) {

        public boolean editable() {
            return "owner".equals(layer);
        }
    }

    /** 一只同伴的模式:{@code ask}、{@code bypass}、{@code observe}。 */
    public record CompanionMode(java.util.UUID companion, String name, String mode) {}

    /** 规则写错了:错在哪(码与现场的原话),界面按码翻成主人的语言。 */
    public record RuleMistake(String code, List<String> args) {}

    /**
     * 权限页读到的整份:服主开没开硬规则、名下每只同伴的模式、三层规则(硬规则、主人层、出厂层,每层内部是服务端排好的
     * deny、ask、allow 次序)。{@code mistake} 只有刚被拒的那次写的答复里有。
     */
    public record Permission(boolean hardRules, List<CompanionMode> modes, List<PermissionRule> rules, RuleMistake mistake) {

        public static final Permission EMPTY = new Permission(true, List.of(), List.of(), null);
    }

    public static Permission permission() {
        boolean hardRules = true;
        List<CompanionMode> modes = new ArrayList<>();
        List<PermissionRule> rules = new ArrayList<>();
        RuleMistake mistake = null;
        for (JsonElement e : entries(Libraries.PERMISSION)) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("mistake")) {
                JsonObject m = o.getAsJsonObject("mistake");
                List<String> args = new ArrayList<>();
                m.getAsJsonArray("args").forEach(a -> args.add(a.getAsString()));
                mistake = new RuleMistake(text(m, "code"), List.copyOf(args));
                continue;
            }
            switch (text(o, "row")) {
                case "settings" -> hardRules = flag(o, "hardRules");
                case "mode" -> modes.add(new CompanionMode(java.util.UUID.fromString(text(o, "companion")),
                        text(o, "name"), text(o, "mode")));
                case "rule" -> rules.add(new PermissionRule(text(o, "layer"), text(o, "table"), text(o, "rule"),
                        "her".equals(text(o, "source")), text(o, "note") == null ? "" : text(o, "note"),
                        o.get("count").getAsInt(), o.get("last").getAsLong()));
                default -> { }
            }
        }
        return new Permission(hardRules, List.copyOf(modes), List.copyOf(rules), mistake);
    }

    /** 加一行规则:{@code table} 是 deny、ask 或 allow。办成与否由服务端答复,交给 {@code onReply}。 */
    public static void addRule(String table, String rule, java.util.function.Consumer<RemoteLibrary.Reply> onReply) {
        RemoteCompanions.library().request(Libraries.PERMISSION, Libraries.PUT, ruleBody(table, rule), onReply);
    }

    /** 删主人层的一行(按规则原文认,不按序号:她可能在两次读之间加过行)。 */
    public static void deleteRule(String table, String rule) {
        RemoteCompanions.library().request(Libraries.PERMISSION, Libraries.DELETE, ruleBody(table, rule));
    }

    /** 清空主人层的规则。 */
    public static void clearRules() {
        RemoteCompanions.library().request(Libraries.PERMISSION, Libraries.RESET, new JsonObject());
    }

    public static void setMode(java.util.UUID companion, String mode) {
        JsonObject body = new JsonObject();
        body.addProperty("companion", companion.toString());
        body.addProperty("mode", mode);
        RemoteCompanions.library().request(Libraries.PERMISSION, Libraries.MODE, body);
    }

    private static JsonObject ruleBody(String table, String rule) {
        JsonObject body = new JsonObject();
        body.addProperty("table", table);
        body.addProperty("rule", rule);
        return body;
    }
}
