package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.mind.LibraryDesk;
import com.dwinovo.numen.mcp.client.McpServerSpec;
import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.provider.ThinkingPlan;
import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.mind.CompanionState;
import com.dwinovo.numen.api.owner.Libraries;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.plugin.PluginRoster;
import com.dwinovo.numen.mcp.client.McpConnections;
import com.dwinovo.numen.api.skin.SkinError;
import com.dwinovo.numen.api.voice.VoiceProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 这台电脑上那几种库在界面眼里的样子:{@link LibraryTables} 里最近一次读到的整表,读成界面直接用的小记录。
 * 这里不存、不缓存,每次现读;存和删经 {@link ClientCompanions#library()} 交给 {@code LibraryDesk} 办。
 */
public final class ClientLibraries {

    private ClientLibraries() {}

    private static JsonArray entries(String kind) {
        return ClientCompanions.library().entries(kind);
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    // ---- 内容库(人设、模型档案、技能、声线、接进来的 MCP 服务器)共有的几项 ----

    /**
     * 一行内容库条目共有的几项:主人有没有把它关掉、是不是 jar 自带的、它和 jar 的关系(改过没有、有没有新版、来源卸载了没有)。
     *
     * @param id 库里的名字({@code 命名空间:名字})
     */
    public record Base(String id, String name, boolean enabled, boolean shipped, boolean modified, boolean update,
                       boolean gone) {}

    private static Base base(JsonObject o) {
        return new Base(text(o, "id"), text(o, "name"), !o.has("enabled") || o.get("enabled").getAsBoolean(),
                flag(o, "shipped"), flag(o, "modified"), flag(o, "update"), flag(o, "gone"));
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

    public static List<Persona> personas() {
        List<Persona> out = new ArrayList<>();
        for (JsonElement e : entries(LibraryDesk.PERSONA)) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Persona(base(o), text(o, "text") == null ? "" : text(o, "text")));
        }
        return out;
    }

    /** 选人设时能选的:没被关掉的。 */
    public static List<Persona> personasInUse() {
        return personas().stream().filter(p -> p.base().enabled()).toList();
    }

    /** 她绑的这条人设在我的库里是哪一条;不是我绑的、或库里没有是 {@code null}(见 {@link #seen})。 */
    public static Persona persona(Bound bound, java.util.UUID viewer) {
        for (Persona p : personas()) {
            if (seen(p.base(), bound, viewer)) {
                return p;
            }
        }
        return null;
    }

    // ---- 她绑的条目在我这里看不看得到 ----

    /**
     * 库里的这一行是不是她绑的那一条。绑定的条目在<b>绑的那个人</b>的库里解析:我绑的,就是我这台电脑上库里的那一条;别人绑的在他的电脑上,
     * 我库里同名的是另一条。
     *
     * @param viewer 这台客户端的玩家
     */
    private static boolean seen(Base row, Bound bound, java.util.UUID viewer) {
        return row.id().equals(bound.id().toString()) && bound.by().equals(viewer);
    }

    // ---- 模型档案 ----

    /**
     * {@code full} 是档案的全部内容(含地址、代理);读不通的条目是 {@code null}。{@code keySet}:这台电脑的钥匙串里有没有它的服务商的 key。
     */
    public record Provider(Base base, String provider, String model, ProviderLibrary.Entry full, boolean keySet) {

        public String id() {
            return base.id();
        }

        public String name() {
            return base.name();
        }

        public boolean hasKey() {
            return keySet;
        }
    }

    public static List<Provider> providers() {
        List<Provider> out = new ArrayList<>();
        for (JsonElement e : entries(LibraryDesk.PROVIDER)) {
            JsonObject o = e.getAsJsonObject();
            Base base = base(o);
            out.add(new Provider(base, text(o, "provider"), text(o, "model"),
                    !flag(o, "unreadable") ? new ProviderLibrary.Entry(base.id(), base.name(),
                            ProviderLibrary.profileFromJson(o)) : null,
                    flag(o, "keySet")));
        }
        return out;
    }

    /** 她绑的这份档案在我的库里是哪一条;不是我绑的、被我关掉的是 {@code null}(见 {@link #seen})。 */
    public static Provider provider(Bound bound, java.util.UUID viewer) {
        for (Provider p : providers()) {
            if (p.base().enabled() && seen(p.base(), bound, viewer)) {
                return p;
            }
        }
        return null;
    }

    /** 选模型档案时能选的:没被关掉的。 */
    public static List<Provider> providersInUse() {
        return providers().stream().filter(p -> p.base().enabled()).toList();
    }

    // ---- 钥匙串 ----

    /** 一个服务商的 key:{@code mine} 是这台电脑钥匙串里的(没有是 null)。 */
    public record KeyRow(String service, String mine) {}

    public static List<KeyRow> keys() {
        List<KeyRow> out = new ArrayList<>();
        for (JsonElement e : entries(LibraryDesk.KEY)) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("service")) {
                out.add(new KeyRow(text(o, "service"), text(o, "mine")));
            }
        }
        return out;
    }

    /** 这个服务商他自己的 key;没存过是空串。 */
    public static String mineKey(String service) {
        return keys().stream().filter(k -> k.service().equals(service)).map(KeyRow::mine)
                .filter(java.util.Objects::nonNull).findFirst().orElse("");
    }

    /**
     * key 按服务商存进这台电脑的钥匙串(不进条目);和已存的不同才发。
     */
    public static void saveKey(String service, String key) {
        String typed = key == null ? "" : key.strip();
        String stored = mineKey(service);
        if (service == null || service.isBlank() || typed.equals(stored)) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("service", service);
        body.addProperty("key", typed);
        ClientCompanions.library().request(LibraryDesk.KEY, Libraries.PUT, body);
    }

    // ---- 型号目录与连通性检测(档案表单问的;目录、端点解析、检测由这台电脑上的桌面算) ----

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
        JsonArray answers = entries(LibraryDesk.CATALOG);
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
        JsonArray results = entries(LibraryDesk.PROVIDER_CHECK);
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
        for (JsonElement e : entries(LibraryDesk.NOTES)) {
            JsonObject o = e.getAsJsonObject();
            if (companion.toString().equals(text(o, "companion"))) {
                out.add(new Note(text(o, "name"), text(o, "description"), text(o, "type"), o.get("day").getAsInt()));
            }
        }
        return out;
    }

    /** 一条札记的全文(主人打开编辑时才问服务端要)。 */
    public record NoteText(String name, String description, String type, String content) {}

    /** 最近一次读到的这条札记的全文;没读到(没问过、服务端说没有)是 {@code null}。 */
    public static NoteText noteText(java.util.UUID companion, String name) {
        for (JsonElement e : entries(LibraryDesk.NOTE)) {
            JsonObject o = e.getAsJsonObject();
            if (companion.toString().equals(text(o, "companion")) && name.equals(text(o, "name"))) {
                return new NoteText(name, text(o, "description"), text(o, "type"), text(o, "content"));
            }
        }
        return null;
    }

    /** 向服务端要这条札记的全文;答复到了({@code onReply})之后用 {@link #noteText} 读。 */
    public static void askNote(java.util.UUID companion, String name,
                               java.util.function.Consumer<LibraryTables.Reply> onReply) {
        ClientCompanions.library().request(LibraryDesk.NOTE, Libraries.LIST, noteBody(companion, name), onReply);
    }

    /**
     * 新增或改一条札记({@code name} 为 {@code null} = 新增,服务端自动起名)。能不能办由服务端判(是不是她的主人),
     * 答复到了({@code onReply})带回办不成的原因。
     */
    public static void saveNote(java.util.UUID companion, String name, String description, String type, String content,
                                java.util.function.Consumer<LibraryTables.Reply> onReply) {
        JsonObject body = noteBody(companion, name);
        body.addProperty("description", description);
        body.addProperty("type", type);
        body.addProperty("content", content);
        ClientCompanions.library().request(LibraryDesk.NOTES, Libraries.PUT, body, onReply);
    }

    /** 删一条札记。 */
    public static void deleteNote(java.util.UUID companion, String name,
                                  java.util.function.Consumer<LibraryTables.Reply> onReply) {
        ClientCompanions.library().request(LibraryDesk.NOTES, Libraries.DELETE, noteBody(companion, name), onReply);
    }

    private static JsonObject noteBody(java.util.UUID companion, String name) {
        JsonObject body = new JsonObject();
        body.addProperty("companion", companion.toString());
        if (name != null) {
            body.addProperty("name", name);
        }
        return body;
    }

    // ---- 主人的定时 ----

    /** 一条定时:{@code every} 是间隔,游戏刻。 */
    public record Schedule(String id, String text, long every) {}

    /** 这只同伴的定时,按服务端给的顺序。 */
    public static List<Schedule> schedules(java.util.UUID companion) {
        List<Schedule> out = new ArrayList<>();
        for (JsonElement e : entries(LibraryDesk.SCHEDULES)) {
            JsonObject o = e.getAsJsonObject();
            if (companion.toString().equals(text(o, "companion"))) {
                out.add(new Schedule(text(o, "id"), text(o, "text"), o.get("every").getAsLong()));
            }
        }
        return out;
    }

    /**
     * 新增({@code id} 为 {@code null})或改一条定时。能不能办、条数与间隔够不够格都由服务端判,答复到了({@code onReply})带回办不成的原因。
     */
    public static void saveSchedule(java.util.UUID companion, String id, String text, long every,
                                    java.util.function.Consumer<LibraryTables.Reply> onReply) {
        JsonObject body = scheduleBody(companion, id);
        body.addProperty("text", text);
        body.addProperty("every", every);
        ClientCompanions.library().request(LibraryDesk.SCHEDULES, Libraries.PUT, body, onReply);
    }

    /** 删一条定时。 */
    public static void deleteSchedule(java.util.UUID companion, String id,
                                      java.util.function.Consumer<LibraryTables.Reply> onReply) {
        ClientCompanions.library().request(LibraryDesk.SCHEDULES, Libraries.DELETE, scheduleBody(companion, id), onReply);
    }

    private static JsonObject scheduleBody(java.util.UUID companion, String id) {
        JsonObject body = new JsonObject();
        body.addProperty("companion", companion.toString());
        if (id != null) {
            body.addProperty("id", id);
        }
        return body;
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

    public static List<Skill> skills() {
        List<Skill> out = new ArrayList<>();
        for (JsonElement e : entries(LibraryDesk.SKILL)) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Skill(base(o), text(o, "skill"), text(o, "description")));
        }
        return out;
    }

    /** 她用得了的一个技能,给命令补全与 {@code /skills} 看的:名字与描述。 */
    public record HerSkill(String name, String description) {}

    /**
     * 这只同伴用得了的技能:她的大脑在我这台电脑上,就是我技能库里没关掉的;分享来的她,技能在她创建者的电脑上,
     * 这里看不到,是空的(界面不能把它说成"她没有技能")。没指明哪一只({@code null}:会话没有单一的主)给的是我自己技能库里的。
     *
     * @param viewer 这台客户端的玩家
     */
    public static List<HerSkill> skillsOf(java.util.UUID companion, java.util.UUID viewer) {
        NumenRoster.Entry her = companion == null ? null : NumenRoster.instance().entry(companion);
        List<HerSkill> out = new ArrayList<>();
        if (her != null && her.sharedWith(viewer)) {
            return out;
        }
        for (Skill skill : skills()) {
            if (skill.enabled()) {
                out.add(new HerSkill(skill.name(), skill.description()));
            }
        }
        return out;
    }

    // ---- 插件 ----

    /**
     * 花名册里的一行(服务端编码的这一份:{@code Libraries#pluginRows})。{@code status} 是此刻的样子,{@code disabled}
     * 是服主的设定,{@code pending} 说两者要重启才对得上;{@code toggle} 是这位玩家改不改得了它的开关;{@code clientMod} 说它的目标模组只在客户端(这一行装没装看这台客户端自己,见
     * {@code ClientMods})。
     */
    public record Plugin(String id, String name, String version, List<String> authors, String target,
                         PluginRoster.Source source, PluginRoster.Status status, String reason, boolean disabled,
                         boolean pending, boolean toggle, boolean clientMod, java.util.Map<PluginRoster.Kind, Integer> adds) {}

    /** 登记顺序。 */
    public static List<Plugin> plugins() {
        List<Plugin> out = new ArrayList<>();
        for (JsonElement e : entries(Libraries.PLUGIN)) {
            JsonObject o = e.getAsJsonObject();
            List<String> authors = new ArrayList<>();
            o.getAsJsonArray("authors").forEach(a -> authors.add(a.getAsString()));
            java.util.Map<PluginRoster.Kind, Integer> adds = new java.util.EnumMap<>(PluginRoster.Kind.class);
            o.getAsJsonObject("adds").entrySet().forEach(add ->
                    adds.put(PluginRoster.Kind.valueOf(add.getKey().toUpperCase(java.util.Locale.ROOT)),
                            add.getValue().getAsInt()));
            out.add(new Plugin(text(o, "id"), text(o, "name"), text(o, "version"), List.copyOf(authors),
                    text(o, "target"), PluginRoster.Source.valueOf(text(o, "source").toUpperCase(java.util.Locale.ROOT)),
                    PluginRoster.Status.valueOf(text(o, "status").toUpperCase(java.util.Locale.ROOT)),
                    text(o, "reason"), flag(o, "disabled"), flag(o, "pending"), flag(o, "toggle"), flag(o, "clientMod"),
                    java.util.Collections.unmodifiableMap(adds)));
        }
        return out;
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

    public static List<Voice> voices() {
        List<Voice> out = new ArrayList<>();
        for (JsonElement e : entries(LibraryDesk.VOICE)) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Voice(base(o), flag(o, "unreadable") ? null : VoiceProfile.fromJson(o)));
        }
        return out;
    }

    /** 选声线时能选的:没被关掉的。 */
    public static List<Voice> voicesInUse() {
        return voices().stream().filter(v -> v.base().enabled() && v.profile() != null).toList();
    }

    /**
     * 她绑的这条声线在我的库里是哪一条;不是我绑的、被我关掉的、读不通的是 {@code null}(见 {@link #seen})——别的主人库里的
     * 声线这台客户端不念,由他的客户端念。
     */
    public static Voice voice(Bound bound, java.util.UUID viewer) {
        for (Voice v : voices()) {
            if (v.base().enabled() && v.profile() != null && seen(v.base(), bound, viewer)) {
                return v;
            }
        }
        return null;
    }

    // ---- 皮肤 ----

    /** 服务器皮肤文件夹里的一张图;图不能当皮肤用时 {@code png} 是空数组、{@code problem} 说为什么。 */
    public record ServerSkin(String name, byte[] png, SkinError problem) {}

    /** 服务器皮肤文件夹里的全部(服务端推来的最近一张表);房主没有这一节。 */
    public static List<ServerSkin> serverSkins() {
        List<ServerSkin> out = new ArrayList<>();
        for (JsonElement e : entries(Libraries.SKIN_FILES)) {
            JsonObject o = e.getAsJsonObject();
            out.add(new ServerSkin(text(o, "name"),
                    o.has("png") ? java.util.Base64.getDecoder().decode(text(o, "png")) : new byte[0],
                    o.has("problem") ? skinError(o.getAsJsonObject("problem")) : null));
        }
        return out;
    }

    private static SkinError skinError(JsonObject problem) {
        List<String> args = new ArrayList<>();
        problem.getAsJsonArray("args").forEach(a -> args.add(a.getAsString()));
        return SkinError.of(text(problem, "code"), args);
    }

    // ---- 偏好 ----

    /** 主动性档位;服务端还没说过是默认档。 */
    public static int initiative() {
        JsonArray prefs = entries(LibraryDesk.PREFS);
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
        ClientCompanions.library().put(LibraryDesk.PREFS, body);
    }

    // ---- 接进来的 MCP 服务器 ----

    /**
     * 一台接进来的 MCP 服务器。{@code connect} 是条目的"连不连",{@code base.enabled} 是主人自己的开关(只管他的同伴看不看得到它的工具)。
     */
    public record McpClientRow(Base base, boolean connect, boolean connected, int toolCount,
                               McpServerSpec spec, McpConnections.Status status, List<String> tools,
                               String error, String authUrl) {

        public String id() {
            return base.id();
        }

        public String name() {
            return base.name();
        }
    }

    public static List<McpClientRow> mcpClients() {
        List<McpClientRow> out = new ArrayList<>();
        for (JsonElement e : entries(LibraryDesk.MCP_CLIENT)) {
            JsonObject o = e.getAsJsonObject();
            if (flag(o, "unreadable")) {
                continue;
            }
            Base base = base(o);
            int count = o.has("toolCount") ? o.get("toolCount").getAsInt() : 0;
            McpConnections.Status status = McpConnections.Status.valueOf(text(o, "status"));
            List<String> tools = new ArrayList<>();
            if (o.has("tools")) {
                o.getAsJsonArray("tools").forEach(t -> tools.add(t.getAsString()));
            }
            out.add(new McpClientRow(base, flag(o, "connect"), flag(o, "connected"), count,
                    McpServerSpec.parse(base.name(), o.getAsJsonObject("spec")),
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
        JsonArray rows = entries(LibraryDesk.MCP_SERVER);
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

    /** 一只同伴一种动作此刻的档位:{@code verb} 是动作词,{@code tier} 是 {@code allow}、{@code ask}、{@code deny}。 */
    public record PermissionTier(java.util.UUID companion, String name, String verb, String tier) {}

    /** 一只同伴一种动作的"不用问"名单里的一项。 */
    public record PermissionListed(java.util.UUID companion, String name, String verb, String entry) {}

    /** 权限页读到的整份:名下每只同伴每种动作的档位(按动作的次序),和各动作名单里的项。 */
    public record Permission(List<PermissionTier> tiers, List<PermissionListed> listed) {

        public static final Permission EMPTY = new Permission(List.of(), List.of());

        /** 有档位的同伴,按名册顺序。 */
        public List<java.util.UUID> companions() {
            return tiers.stream().map(PermissionTier::companion).distinct().toList();
        }
    }

    public static Permission permission() {
        List<PermissionTier> tiers = new ArrayList<>();
        List<PermissionListed> listed = new ArrayList<>();
        for (JsonElement e : entries(Libraries.PERMISSION)) {
            JsonObject o = e.getAsJsonObject();
            java.util.UUID companion = java.util.UUID.fromString(text(o, "companion"));
            switch (text(o, "row")) {
                case "tier" -> tiers.add(new PermissionTier(companion, text(o, "name"), text(o, "verb"), text(o, "tier")));
                case "listed" -> listed.add(new PermissionListed(companion, text(o, "name"), text(o, "verb"),
                        text(o, "entry")));
                default -> { }
            }
        }
        return new Permission(List.copyOf(tiers), List.copyOf(listed));
    }

    /** 设一只同伴一种动作的档位。 */
    public static void setTier(java.util.UUID companion, String verb, String tier) {
        JsonObject body = body(companion);
        body.addProperty("verb", verb);
        body.addProperty("tier", tier);
        ClientCompanions.library().request(Libraries.PERMISSION, Libraries.TIER, body);
    }

    /** 给一只同伴套一个预设:{@code observe}、{@code bypass}、{@code factory}。 */
    public static void applyPreset(java.util.UUID companion, String preset) {
        JsonObject body = body(companion);
        body.addProperty("preset", preset);
        ClientCompanions.library().request(Libraries.PERMISSION, Libraries.PRESET, body);
    }

    /** 从一只同伴一种动作的名单里删一项。 */
    public static void deleteListed(java.util.UUID companion, String verb, String entry) {
        JsonObject body = body(companion);
        body.addProperty("verb", verb);
        body.addProperty("entry", entry);
        ClientCompanions.library().request(Libraries.PERMISSION, Libraries.DELETE, body);
    }

    private static JsonObject body(java.util.UUID companion) {
        JsonObject body = new JsonObject();
        body.addProperty("companion", companion.toString());
        return body;
    }
}
