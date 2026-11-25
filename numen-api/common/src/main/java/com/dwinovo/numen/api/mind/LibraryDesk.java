package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.agent.llm.ConnectionProfile;
import com.dwinovo.numen.api.agent.llm.ModelCatalog;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.dwinovo.numen.api.agent.skill.SkillInfo;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.mind.OwnerSchedules;
import com.dwinovo.numen.api.mind.Slot;
import com.dwinovo.numen.api.keys.Keychain;
import com.dwinovo.numen.api.keys.Keys;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryKind;
import com.dwinovo.numen.api.library.Shelf;
import com.dwinovo.numen.api.library.Shipped;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.voice.VoiceProfile;
import com.dwinovo.numen.mcp.client.McpConnections;
import com.dwinovo.numen.mcp.client.McpServerSpec;
import com.dwinovo.numen.mcp.client.auth.McpOAuth;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 主人客户端上她的大脑的配置的读写:人设、模型档案、声线、技能、钥匙串、札记、定时、偏好、接进来的 MCP 服务器。界面一问一答,答复是整表
 * (JSON 数组)——表和请求的样子是界面({@code ClientLibraries})认的那一套,编解码只在这一处与那一处。这些东西都在这台电脑上,
 * 不经服务器:库是 {@link Library}(随模组带来的默认内容与自己写的在同一个货架里),key 在钥匙串里。
 *
 * <p>线程:大脑的线程(客户端主线程)。
 */
public final class LibraryDesk {

    public static final String PERSONA = "persona";
    public static final String PROVIDER = "provider";
    public static final String MCP_CLIENT = "mcp-client";
    /** 外部大脑的端点:接入地址、开关、超时、不暴露的工具。在这台电脑上,主人自己的设置。 */
    public static final String MCP_SERVER = "mcp-server";
    public static final String PREFS = "prefs";
    public static final String SKILL = "skill";
    public static final String NOTES = "notes";
    /** 一条札记的全文:按 {@code companion}、{@code name} 问,答一行;问不到(不是他的同伴、没有这条)是空表。 */
    public static final String NOTE = "note";
    /** 主人给她们定的定时:一行一条(companion、id、text、every 游戏刻)。 */
    public static final String SCHEDULES = "schedules";
    public static final String CATALOG = "catalog";
    public static final String PROVIDER_CHECK = "provider-check";
    public static final String KEY = "key";
    public static final String VOICE = "voice";

    public static final String LIST = "list";
    public static final String PUT = "put";
    public static final String DELETE = "delete";
    /** 内容库专有:重扫目录。 */
    public static final String RELOAD = "reload";
    /** 内容库专有:主人自己的开关(记在他的偏好里)。 */
    public static final String ENABLE = "enable";
    public static final String DISABLE = "disable";
    /** MCP 服务器专有:条目的"连不连"。 */
    public static final String CONNECT = "connect";
    public static final String DISCONNECT = "disconnect";
    /** 内容库专有:jar 带来了新版而他改过自带内容时,采用新版(换掉他的)或不采用(这一版不再提示)。 */
    public static final String ACCEPT = "accept";
    public static final String DISMISS = "dismiss";
    /** 内容库专有:改过的自带条目丢掉改动,取回 jar 里的默认。 */
    public static final String RESTORE = "restore";
    /** MCP 服务器专有:交回授权回调网址。 */
    public static final String AUTH = "auth";
    /** 连通性检测专有:试连一份草稿。 */
    public static final String RUN = "run";

    /** 新建成功的答复里附的一行 {@code {"created": 新条目的 id}};改已有条目的答复不带。 */
    public static final String CREATED = "created";

    /**
     * 一次读写的结果。
     *
     * @param entries 这位玩家此刻看得到的整表(JSON 数组)
     * @param problem 办不成的原因(给界面和日志);办成是 {@code null}
     * @param later   还要等一会儿才有的结局(连通性检测的那次请求):到了再作为同一种库的又一张表送出去(请求号随这一张,它才是答复);没有是 {@code null}
     */
    public record Result(String entries, String problem, java.util.concurrent.CompletableFuture<String> later) {

        public Result(String entries, String problem) {
            this(entries, problem, null);
        }
    }

    /** 外部大脑的端点只在连着服务器时有:没连着时问它得到的说法。 */
    private static final String NOT_CONNECTED = "not connected to a server";

    /** 这台电脑上各样东西的出处;测试给临时目录里的。 */
    public interface Sources {

        /** 这种内容库。 */
        Library library(LibraryKind kind);

        /** 模型档案库。 */
        ProviderLibrary providers();

        /** 偏好:主动性档位与各库条目的开关。 */
        ClientPrefs prefs();

        /** 她们的大脑,按同伴。 */
        List<LoopHost> brains();

        /** 随模组同步的清单。 */
        Shipped.Manifest manifest();

        /** 主人的钥匙串。 */
        Keychain keys();

        /** 接进来的 MCP 服务器的连接;没连着服务器是 {@code null}。 */
        ClientMcp mcp();

        /** 外部大脑的端点;没连着服务器是 {@code null}。 */
        McpMode external();
    }

    private final Sources sources;
    /** 型号目录与连通性检测问的目录。 */
    private final ModelCatalog catalog;

    public LibraryDesk(Sources sources, ModelCatalog catalog) {
        this.sources = sources;
        this.catalog = catalog;
    }

    /**
     * @param owner 发请求的玩家
     * @param body  存和删带的 JSON;列不用
     */
    public Result handle(UUID owner, String kind, String op, JsonObject body) {
        if (PROVIDER_CHECK.equals(kind)) {
            return check(owner, op, body);
        }
        Done done = switch (kind) {
            case PERSONA -> contentLibrary(PersonaLibrary.KIND, op, body,
                    b -> required(b, "text", "a persona needs a text"),
                    (from, to) -> rebind(Slot.PERSONA, from, to));
            case PROVIDER -> contentLibrary(ProviderLibrary.KIND, op, body,
                    b -> ProviderLibrary.write(ProviderLibrary.profileFromJson(b)),
                    (from, to) -> rebind(Slot.PROVIDER, from, to));
            case SKILL -> contentLibrary(SkillRegistry.KIND, op, body, null, (from, to) -> { });
            case VOICE -> contentLibrary(VoiceProfile.KIND, op, body,
                    b -> VoiceProfile.fromJson(b).write(),
                    (from, to) -> rebind(Slot.VOICE, from, to));
            case KEY -> key(op, body);
            case MCP_CLIENT -> mcpClient(op, body);
            case MCP_SERVER -> Done.of(mcpServer(op, body));
            case PREFS -> Done.of(prefs(op, body));
            case NOTES -> notes(owner, op, body);
            case SCHEDULES -> schedules(owner, op, body);
            case NOTE, CATALOG -> Done.of(LIST.equals(op) ? null : "unknown operation");
            default -> Done.of("unknown library");
        };
        JsonArray entries = JsonParser.parseString(view(owner, kind, body)).getAsJsonArray();
        if (done.extra() != null) {
            entries.add(done.extra());
        }
        return new Result(entries.toString(), done.problem(), null);
    }

    /** 一次操作的结局:办不成的原因,以及要随答复附在整表后面带给这位玩家的一行(提醒、规则错在哪)。 */
    private record Done(String problem, JsonObject extra) {

        static Done ok() {
            return new Done(null, null);
        }

        /** {@code problem} 为 {@code null} 是办成了。 */
        static Done of(String problem) {
            return new Done(problem, null);
        }

        static Done problem(String problem) {
            return new Done(problem, null);
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
                ProviderForm.check(catalog, sources.providers().keys(), body).thenApply(state -> {
                    JsonArray done = new JsonArray();
                    done.add(state);
                    return done.toString();
                }));
    }

    /** 此刻的整表。 */
    public String view(UUID owner, String kind) {
        return view(owner, kind, new JsonObject());
    }

    /** 同上;目录这种按问题答的库,{@code ask} 是问题(见 {@link ProviderForm#answer})。 */
    private String view(UUID owner, String kind, JsonObject ask) {
        JsonArray out = new JsonArray();
        switch (kind) {
            case PERSONA -> rows(sources.library(PersonaLibrary.KIND), out, (entry, row) ->
                    row.addProperty("text", entry.text().strip()));
            case PROVIDER -> providerRows(out);
            case SKILL -> rows(sources.library(SkillRegistry.KIND), out, (entry, row) -> {
                SkillInfo info = SkillRegistry.read(entry.path());
                row.addProperty("skill", info == null ? entry.id().name() : info.name());
                row.addProperty("description", info == null || info.description() == null ? "" : info.description());
            });
            case VOICE -> rows(sources.library(VoiceProfile.KIND), out, (entry, row) -> {
                try {
                    VoiceProfile.read(entry.text()).toJson().entrySet().forEach(e -> row.add(e.getKey(), e.getValue()));
                } catch (RuntimeException unreadable) {
                    row.addProperty("unreadable", true);
                }
            });
            case KEY -> keyRows(out);
            case MCP_CLIENT -> mcpRows(out);
            case MCP_SERVER -> out.add(mcpServerView());
            case CATALOG -> out.add(ProviderForm.answer(catalog, ask));
            case PREFS -> out.add(sources.prefs().toJson());
            case NOTES -> sources.brains().forEach(brain -> brain.notes().index()
                    .forEach(note -> out.add(noteRow(brain.companion(), note, false))));
            case SCHEDULES -> sources.brains().forEach(brain -> brain.schedules()
                    .forEach(schedule -> out.add(scheduleRow(brain.companion(), schedule))));
            case NOTE -> {
                UUID companion = ownedCompanion(owner, ask);
                NoteBook.Note note = companion == null ? null : brain(companion).notes().read(string(ask, "name"));
                if (note != null) {
                    out.add(noteRow(companion, note, true));
                }
            }
            default -> { }
        }
        return out.toString();
    }

    private static JsonObject scheduleRow(UUID companion, OwnerSchedules.Schedule s) {
        JsonObject o = new JsonObject();
        o.addProperty("companion", companion.toString());
        o.addProperty("id", s.id());
        o.addProperty("text", s.text());
        o.addProperty("every", s.every());
        return o;
    }

    /** 一条札记的一行:索引那几项,{@code full} 时再带全文。 */
    private static JsonObject noteRow(UUID companion, NoteBook.Note note, boolean full) {
        JsonObject o = new JsonObject();
        o.addProperty("companion", companion.toString());
        o.addProperty("name", note.name());
        o.addProperty("description", note.description());
        o.addProperty("type", note.type());
        o.addProperty("day", note.day());
        if (full) {
            o.addProperty("content", note.content());
        }
        return o;
    }

    /** 请求里 {@code companion} 写的那只,前提是它在这位玩家名下;不是(没写、写坏了、别人的)是 {@code null}。 */
    private UUID ownedCompanion(UUID owner, JsonObject body) {
        String text = string(body, "companion");
        UUID companion;
        try {
            companion = text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
        return companion != null && sources.brains().stream().anyMatch(b -> b.companion().equals(companion)) ? companion : null;
    }

    // ---- 她的札记:主人增删改 ----

    /**
     * 主人改她的札记。写与删都走 {@link NoteBook}(她的工具用的那一本)的 write/forget,只有这一条写路径;她开着脑子时,
     * 往她的收件箱放一条不叫醒她的知会,她下一次开口前读到。
     */
    private Done notes(UUID owner, String op, JsonObject body) {
        if (LIST.equals(op)) {
            return Done.ok();
        }
        if (!PUT.equals(op) && !DELETE.equals(op)) {
            return Done.problem("unknown operation");
        }
        UUID companion = ownedCompanion(owner, body);
        if (companion == null) {
            return Done.problem("not your companion");
        }
        NoteBook book = brain(companion).notes();
        java.util.Optional<LoopHost> loop = java.util.Optional.ofNullable(brain(companion));
        String name = string(body, "name");
        if (DELETE.equals(op)) {
            NoteBook.Note gone = book.read(name);
            if (gone == null || !book.forget(name)) {
                return Done.problem("no such note");
            }
            loop.ifPresent(l -> l.ownerForgotNote(gone));
            return Done.ok();
        }
        boolean replaced = name != null && book.read(name) != null;
        NoteBook.Note note;
        try {
            note = book.write(name, string(body, "description"), NoteBook.typeOrDefault(string(body, "type")),
                    string(body, "content"));
        } catch (IllegalArgumentException refused) {
            return Done.problem(refused.getMessage());
        }
        loop.ifPresent(l -> l.ownerWroteNote(note, replaced));
        return Done.ok();
    }

    // ---- 主人的定时:增删改 ----

    /** 主人增删改她的定时。只有她的主人({@link #ownedCompanion})能做;存与删落在她的家里({@link OwnerSchedules})。 */
    private Done schedules(UUID owner, String op, JsonObject body) {
        if (LIST.equals(op)) {
            return Done.ok();
        }
        if (!PUT.equals(op) && !DELETE.equals(op)) {
            return Done.problem("unknown operation");
        }
        UUID companion = ownedCompanion(owner, body);
        if (companion == null) {
            return Done.problem("not your companion");
        }
        java.util.Optional<LoopHost> loop = java.util.Optional.ofNullable(brain(companion));
        if (loop.isEmpty()) {
            return Done.problem("she has no brain here");
        }
        String id = string(body, "id");
        if (DELETE.equals(op)) {
            return loop.get().deleteSchedule(id) ? Done.ok() : Done.problem("no such schedule");
        }
        long every;
        try {
            every = Long.parseLong(String.valueOf(string(body, "every")));
            loop.get().putSchedule(id, string(body, "text"), every);
        } catch (IllegalArgumentException refused) {
            return Done.problem(refused.getMessage());
        }
        return Done.ok();
    }

    // ---- 内容库:同一套操作、同一种行 ----

    /** 一条的内容库行共有的几项;{@code extra} 补各库自己的。 */
    private static void rows(Library library, JsonArray out,
                             java.util.function.BiConsumer<Library.Entry, JsonObject> extra) {
        for (Library.Entry entry : library.entries()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", entry.id().toString());
            row.addProperty("name", entry.id().name());
            row.addProperty("namespace", entry.id().namespace());
            row.addProperty("shipped", entry.provenance().shipped());
            row.addProperty("enabled", entry.enabled());
            row.addProperty("modified", entry.provenance().modified());
            row.addProperty("update", entry.provenance().updateAvailable());
            row.addProperty("gone", entry.provenance().sourceGone());
            extra.accept(entry, row);
            out.add(row);
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
     * 内容库的操作。开关记在他自己的偏好里。
     *
     * @param content 请求的正文变成文件的正文;这种库不能经协议存就是 {@code null}
     * @param renamed 某一条改了名(id 随之更换)时,他绑的旧 id 改指新的
     */
    private Done contentLibrary(LibraryKind kind, String op, JsonObject body, Content content,
                                java.util.function.BiConsumer<EntryId, EntryId> renamed) {
        Library library = sources.library(kind);
        EntryId id = EntryId.parse(string(body, "id"));
        switch (op) {
            case LIST:
                return Done.ok();
            case RELOAD:
                library.reload();   // 目录里的文件是真源,外部编辑器改过就重扫
                return Done.ok();
            case ENABLE:
            case DISABLE:
                if (id == null || library.findAny(id) == null) {
                    return Done.problem("no such entry");
                }
                library.setEnabled(id, ENABLE.equals(op));
                return Done.ok();
            case ACCEPT:
            case DISMISS:
                if (id == null || library.shelf().get(id) == null) {
                    return Done.problem("no such entry");
                }
                if (ACCEPT.equals(op)) {
                    sources.manifest().accept(kind, id);
                } else {
                    sources.manifest().dismiss(kind, id);
                }
                library.reload();   // 清单换了磁盘上的文件,内存里的表跟上
                return Done.ok();
            case RESTORE:
                if (id == null || library.shelf().get(id) == null) {
                    return Done.problem("no such entry");
                }
                if (!sources.manifest().restore(kind, id)) {
                    return Done.problem("that entry has no default to go back to");
                }
                library.reload();   // 清单换了磁盘上的文件,内存里的表跟上
                return Done.ok();
            case PUT:
            case DELETE:
                break;
            default:
                return Done.problem("unknown operation");
        }
        Shelf shelf = library.shelf();
        if (DELETE.equals(op)) {
            if (id == null || shelf.get(id) == null) {
                return Done.problem("no such entry");
            }
            shelf.delete(id);
            return Done.ok();
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
                : shelf.unique(old != null ? old.namespace() : EntryId.MINE, clean);
        shelf.write(target, text);
        if (old != null && !old.equals(target)) {
            shelf.delete(old);
            renamed.accept(old, target);   // 某条改了名:id 就是名字,他绑着它的那些绑定得重新指过去
        }
        if (old != null) {
            return Done.ok();
        }
        // 新建的:id 由服务端按名字起,客户端要凭它把新条目绑给当前同伴,所以随答复明说
        JsonObject created = new JsonObject();
        created.addProperty(CREATED, target.toString());
        return new Done(null, created);
    }

    // ---- 皮肤:文件夹里的 png,用时才签 ----

    // ---- 模型档案与钥匙串 ----

    /** 每位主人自己的偏好;只认认得的字段。 */
    private String prefs(String op, JsonObject body) {
        switch (op) {
            case LIST:
                return null;
            case PUT:
                if (body.has("initiative")) {
                    sources.prefs().setInitiative(body.get("initiative").getAsInt());
                }
                return null;
            default:
                return "unknown operation";
        }
    }

    // ---- 插件 ----

    // ---- 接进来的 MCP 服务器 ----

    /**
     * 接进来的 MCP 服务器的行:内容库共有的几项,加连接状态与配置的全貌(地址、命令行、请求头、授权网址)。{@code connect} 是条目的
     * "连不连"(写在条目里),和 {@code enabled}(主人自己的开关,只管他的同伴看不看得到这台的工具)是两回事。
     */
    private void mcpRows(JsonArray out) {
        rows(sources.library(McpClientConfig.KIND), out, (entry, row) -> {
            McpServerSpec spec;
            try {
                spec = McpServerSpec.read(entry.id().name(), entry.text());
            } catch (RuntimeException unreadable) {
                row.addProperty("unreadable", true);
                return;
            }
            McpConnections manager = sources.mcp() == null ? null : sources.mcp().connections();
            McpConnections.ServerHandle handle = manager == null ? null : manager.handle(entry.id().toString());
            McpConnections.Status status = handle == null ? McpConnections.Status.DISABLED : handle.status();
            row.addProperty("type", spec.type());
            row.addProperty("connect", spec.enabled());
            row.addProperty("status", status.name());
            row.addProperty("connected", status == McpConnections.Status.CONNECTED);
            row.addProperty("toolCount", handle == null ? 0 : handle.toolCount());
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
        });
    }

    /** 外部大脑端点的整份状态:配置、谁连着、起服失败的原因。令牌不在这里:它们是主人在聊天里用 /token 发的。 */
    private JsonObject mcpServerView() {
        McpMode mode = sources.external();
        if (mode == null) {
            JsonObject offline = new JsonObject();
            offline.addProperty("enabled", false);
            offline.addProperty("lastError", NOT_CONNECTED);
            return offline;
        }
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

    /** 外部大脑端点:开关、地址与超时、不暴露的工具,哪些字段在 {@code put} 里就改哪些。起服失败的原因当作办不成的原因交回。 */
    private String mcpServer(String op, JsonObject body) {
        if (LIST.equals(op)) {
            return null;
        }
        if (!PUT.equals(op)) {
            return "unknown operation";
        }
        McpMode mode = sources.external();
        if (mode == null) {
            return NOT_CONNECTED;
        }
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

    /**
     * 接进来的 MCP 服务器的操作:内容库那一套,外加条目的连上与断开({@link #CONNECT}、{@link #DISCONNECT})
     * 和交回授权回调网址({@link #AUTH})。库变了连接跟着变。
     */
    private Done mcpClient(String op, JsonObject body) {
        if (AUTH.equals(op)) {
            return Done.problem(McpOAuth.complete(string(body, "url")));
        }
        Done done;
        if (CONNECT.equals(op) || DISCONNECT.equals(op)) {
            done = connect(CONNECT.equals(op), body);
        } else {
            done = contentLibrary(McpClientConfig.KIND, op, body, b -> {
                try {
                    return McpServerSpec.parse(string(b, "name"), b).write();
                } catch (IllegalArgumentException refused) {
                    throw refused;
                } catch (RuntimeException unreadable) {
                    throw new IllegalArgumentException("unreadable MCP server");
                }
            }, (from, to) -> { });
        }
        if (!LIST.equals(op) && sources.mcp() != null) {
            sources.mcp().reconcile();
        }
        return done;
    }

    private Done connect(boolean on, JsonObject body) {
        Shelf shelf = sources.library(McpClientConfig.KIND).shelf();
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
        return Done.ok();
    }

    /** 模型档案的行:内容库共有的几项加档案的字段,和这个服务有没有 key。 */
    private void providerRows(JsonArray out) {
        Keys keys = sources.providers().keys();
        rows(sources.library(ProviderLibrary.KIND), out, (entry, row) -> {
            ConnectionProfile profile;
            try {
                profile = ProviderLibrary.read(entry.text());
            } catch (RuntimeException unreadable) {
                row.addProperty("unreadable", true);
                return;
            }
            ProviderLibrary.profileToJson(profile).entrySet().forEach(e -> row.add(e.getKey(), e.getValue()));
            row.addProperty("keySet", keys.find(profile.provider()) != null);
        });
    }

    /** 钥匙串页与 {@code /numen key} 补全列的服务商:档案里用到的、已存过 key 的。 */
    public Set<String> keyServices() {
        Set<String> services = new TreeSet<>();
        for (Library.Entry entry : sources.library(ProviderLibrary.KIND).entries()) {
            try {
                String service = ProviderLibrary.read(entry.text()).provider();
                if (service != null && EntryId.segment(service)) {
                    services.add(service);
                }
            } catch (RuntimeException unreadable) {
                // 读不通的档案没有服务可言
            }
        }
        services.addAll(sources.keys().services());
        return services;
    }

    /** 钥匙串:用得到的那些服务商,各自有没有 key。 */
    private void keyRows(JsonArray out) {
        for (String service : keyServices()) {
            JsonObject row = new JsonObject();
            row.addProperty("service", service);
            String mine = sources.keys().get(service);
            if (mine != null) {
                row.addProperty("mine", mine);
            }
            out.add(row);
        }
    }

    /** 存、删一把 key:存在主人自己的钥匙串里(空 key 是删掉)。 */
    private Done key(String op, JsonObject body) {
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
        sources.keys().put(service, DELETE.equals(op) || string(body, "key") == null ? "" : string(body, "key"));
        return Done.ok();
    }

    /** 她们的大脑里改了绑定的:这一项绑的正是 {@code from} 的,改指 {@code to}。 */
    private void rebind(Slot slot, EntryId from, EntryId to) {
        for (LoopHost brain : sources.brains()) {
            Bound bound = brain.bound(slot);
            if (bound != null && bound.id().equals(from)) {
                brain.repoint(slot, bound, to);
            }
        }
    }

    /** 这只同伴的大脑;不是她的(没有)是 {@code null}。 */
    private LoopHost brain(UUID companion) {
        return sources.brains().stream().filter(b -> b.companion().equals(companion)).findFirst().orElse(null);
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
