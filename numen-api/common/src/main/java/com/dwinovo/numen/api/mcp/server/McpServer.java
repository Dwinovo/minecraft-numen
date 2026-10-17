package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.inbox.ExternalWait;
import com.dwinovo.numen.api.agent.llm.ToolOutcome;
import com.dwinovo.numen.api.agent.prompt.NumenPrompts;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A minimal MCP (Model Context Protocol) server, hand-rolled as JSON-RPC 2.0
 * over the JDK's built-in HTTP server — no third-party MCP SDK, no Reactor. It
 * implements exactly the surface an external agent needs to drive companions:
 * {@code initialize}, {@code tools/list}, {@code tools/call}, {@code ping}, and
 * the {@code notifications/initialized} handshake.
 *
 * <h2>Transport</h2>
 * Binds a loopback HTTP endpoint at {@code /mcp}. Claude Desktop can't dial an
 * HTTP MCP server directly, so the user points it at this endpoint through the
 * {@code mcp-remote} stdio bridge; every JSON-RPC frame arrives here as an HTTP
 * POST and its response goes straight back in the HTTP body.
 *
 * <h2>Tool surface</h2>
 * Every Numen API tool (from {@link ToolRegistry}, minus the config's hidden set) —
 * the script tool, the skill tool, the todo tool, plus the tools of remote MCP servers the owner connected — is
 * advertised with an extra {@code companion} argument, and calls route to
 * {@link ExternalHands#invoke}: a program runs through the companion's own
 * dispatcher and the call returns its receipt when it ends. Three management tools — {@code list_companions},
 * {@code create_companion}, {@code delete_companion} — wrap the hands' roster
 * methods. There is no take-control handshake: 模式开启期间内置大脑一轮都不开
 * (见 {@link McpMode}), 所以外部驱动者直接派活即可。Since every call is addressed
 * to a companion and each body runs its tasks independently, an agent can drive
 * several companions in parallel.
 */
public final class McpServer {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SERVER_VERSION = "0.1.0";
    /** Roster / create / delete are fast; only tool actions use the config timeout. */
    private static final int CONTROL_TIMEOUT_SECONDS = 10;
    /** get_events 长等待：外脑每调一次工具就是一次完整的模型请求，所以没事就一直等，有事（会叫醒她的条目，
     *  判据见 {@code ExternalWait}）立刻返回。最长 50 秒，给桥接层（mcp-remote 等）和客户端的请求超时留余量
     *  （Codex 的工具超时默认 60 秒）；到点返回空，外脑再调一次接着等。 */
    private static final int MAX_WAIT_SECONDS = 50;

    /**
     * 外接大脑读的那条身体规则:内脑读的同一句({@link NumenPrompts#ONE_BODY}),接上这一侧怎么把几件活排开——外接大脑的
     * 调用没有"同一轮",每一条当场执行,要一件做完再派下一件就自己等身体空闲。说明与接入提示词({@link McpAccessPrompt})
     * 都用这一句。
     */
    static final String ONE_BODY = NumenPrompts.ONE_BODY + " A program waits for each body task it starts, so jobs "
            + "written one after another in one program run one after another.";

    /**
     * Sent to the connecting agent in the {@code initialize} handshake (MCP's
     * {@code instructions} field) — what Numen is and how to drive it, so any
     * client gets the essentials without a separately-installed skill.
     */
    private static final String INSTRUCTIONS = """
            Numen companions are AI-controlled, player-like characters inside a live Minecraft game. \
            Through this server you take control of a companion's body and play the game as it — perceive, \
            move, mine, build, craft, fight. You ARE its brain while this server runs: the built-in AI \
            stands aside entirely, and even the owner's in-game chat reaches you (via get_events) instead \
            of it. Drive the body directly — there is no 'take control' handshake.

            Loop: (1) list_companions to see who is live — create_companion by name to summon a new one, \
            delete_companion to dismiss one for good; (2) everything else is one tool, %s: a program \
            whose functions are the companion's API — perceive with numen.status.self(), numen.scan.blocks(...), \
            numen.scan.entities(...); act with numen.move.to(...), numen.work.dig(...), numen.build.place(...), numen.fight.attack(...), \
            numen.inv.craft(...), …; numen.api.help("numen.work") lists a group's functions and numen.api.help("numen.work.dig") gives one \
            function's full help. A program returns one receipt when it ends: how it ended, then stderr \
            (what the body did and what failed, one entry per call that had something to say), what it returned \
            and stdout (what it printed). It waits for each body task it starts to finish, so a long \
            job returns when it is done, with the task's account of what it changed in its stderr entry. Every call takes a 'companion' argument (name or id), so each call targets \
            one companion; just drive it, there is no take-control step. The %s tool loads one of the \
            companion's skills — the workflow guide for one kind of task — and returns its text; a name it \
            does not know answers with the list of the skills it can use. The %s tool writes down your plan for \
            work of several phases (the whole plan each call, replacing the last one); the owner sees it as a \
            checklist. The %s tool keeps the companion's own notes (remember, recall, forget); their index \
            comes with every turn of its own brain.

            Rules: survival mode — the API does only what a real player can (mine to get stone; there is no \
            give or setblock). You are blind between calls, so perceive before and after acting. %s You can \
            drive several companions in parallel. Modded blocks, items, and GUIs (Create, AE2, Mekanism) \
            work natively.

            You also carry the companion's conversation. To wait for the owner to speak or for something to happen, \
            call get_events(companion): it returns as soon as there is news and otherwise waits up to %d seconds, \
            then answers that nothing came, so call it again to keep waiting. While you run programs, events that \
            arrive come back with the program's receipt, after the receipt text. The owner speaking to the companion \
            (in-game chat or voice) arrives as a <query> and stops a running program between two calls; the task it \
            was waiting for keeps running, and its end arrives as a task_finished <event> among the world \
            happenings. Reply with say(companion, text): the words appear in-game as the companion's chat line, \
            speech bubble, and voice. Keep your own conversation history — the game stores none for you; events \
            queue up until you take them.""".formatted(
            com.dwinovo.numen.api.agent.script.ScriptEngine.IN_USE.toolName(), com.dwinovo.numen.api.agent.tool.SkillTool.NAME,
            com.dwinovo.numen.api.agent.tool.TodoTool.NAME, com.dwinovo.numen.api.agent.tool.MemoryTool.NAME, ONE_BODY,
            MAX_WAIT_SECONDS);

    private final McpConfig config;
    private final ExternalHands hands;
    private final Gson gson = new Gson();
    private HttpServer http;

    public McpServer(McpConfig config, ExternalHands hands) {
        this.config = config;
        this.hands = hands;
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        http.createContext("/mcp", this::handle);
        // get_events 长轮询会让整条线程停靠最长 50 秒，动作类工具也会阻到任务结束——
        // 池得容下几个同伴各自的轮询加并行动作。
        http.setExecutor(Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "numen-mcp-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    public void stop() {
        if (http != null) http.stop(0);
    }

    // ---- HTTP layer ----

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, "");
                return;
            }
            if (!authorized(ex)) {
                respond(ex, 401, "");
                return;
            }
            // 任何通过鉴权的请求都算"对方还在"——面板的活跃时间靠它,ping 也算数。
            McpMode.instance().touch();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(body);
            } catch (RuntimeException parseErr) {
                respondJson(ex, errorResponse(null, -32700, "parse error: " + parseErr.getMessage()));
                return;
            }

            if (parsed.isJsonArray()) {   // JSON-RPC batch
                JsonArray out = new JsonArray();
                for (JsonElement el : parsed.getAsJsonArray()) {
                    JsonObject r = dispatch(el.getAsJsonObject());
                    if (r != null) out.add(r);
                }
                if (out.isEmpty()) respond(ex, 202, "");
                else respondJson(ex, out);
            } else {
                JsonObject r = dispatch(parsed.getAsJsonObject());
                if (r == null) respond(ex, 202, "");   // notification: no response
                else respondJson(ex, r);
            }
        } catch (RuntimeException fatal) {
            Constants.LOG.warn("[numen-mcp] request failed: {}", fatal.toString());
            try { respond(ex, 500, ""); } catch (IOException ignored) {}
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex) {
        if (config.token().isBlank()) return true;
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.equals("Bearer " + config.token())) return true;
        String query = ex.getRequestURI().getQuery();
        return query != null && query.contains("token=" + config.token());
    }

    private void respondJson(HttpExchange ex, JsonElement json) throws IOException {
        byte[] bytes = gson.toJson(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ---- JSON-RPC dispatch ----

    /** @return the response object, or null for a notification (no id → no reply). */
    private JsonObject dispatch(JsonObject req) {
        JsonElement id = req.get("id");
        String method = req.has("method") ? req.get("method").getAsString() : "";

        // Notifications carry no id and expect no response.
        if (id == null || id.isJsonNull()) {
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> okResponse(id, initializeResult(req));
                case "ping" -> okResponse(id, new JsonObject());
                case "tools/list" -> okResponse(id, toolsListResult());
                case "tools/call" -> okResponse(id, toolsCallResult(req));
                default -> errorResponse(id, -32601, "method not found: " + method);
            };
        } catch (RuntimeException ex) {
            return errorResponse(id, -32603, "internal error: " + ex.getMessage());
        }
    }

    private JsonObject initializeResult(JsonObject req) {
        String clientProto = PROTOCOL_VERSION;
        if (req.has("params") && req.get("params").isJsonObject()) {
            JsonObject p = req.getAsJsonObject("params");
            if (p.has("protocolVersion")) clientProto = p.get("protocolVersion").getAsString();
            // 握手报的名字就是面板上"谁接进来了"的那一行。
            McpMode.instance().handshake(clientLabel(p));
        }
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", clientProto);
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        result.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "numen-mcp");
        info.addProperty("version", SERVER_VERSION);
        result.add("serverInfo", info);
        result.addProperty("instructions", INSTRUCTIONS);
        return result;
    }

    /** {@code params.clientInfo} → "Claude Desktop 1.2.3";没报名字就叫 unknown client。 */
    private static String clientLabel(JsonObject params) {
        if (!params.has("clientInfo") || !params.get("clientInfo").isJsonObject()) return "unknown client";
        JsonObject info = params.getAsJsonObject("clientInfo");
        String name = info.has("name") ? info.get("name").getAsString() : "unknown client";
        String version = info.has("version") ? info.get("version").getAsString() : "";
        return version.isBlank() ? name : name + " " + version;
    }

    // ---- tools/list ----

    private JsonObject toolsListResult() {
        JsonArray tools = new JsonArray();

        tools.add(toolDef("list_companions",
                "List the owner's live Minecraft companions (name + id). Call this first to see who you can drive.",
                objectSchema(null, false)));
        tools.add(toolDef("create_companion",
                "Summon a new companion into the world by name (3–16 letters, digits, or underscore). It arrives "
                        + "in a moment; call list_companions to confirm, then drive it directly — no 'take control' step.",
                requiredStringSchema("name",
                        "The new companion's name (3–16 letters, digits, or underscore).")));
        tools.add(toolDef("delete_companion",
                "Permanently dismiss a companion — it drops its inventory, armor and accessories and is gone for good. "
                        + "Takes its name or id.",
                requiredStringSchema("companion",
                        "Which companion to dismiss — its name or id (see list_companions).")));
        tools.add(toolDef("get_events",
                "Wait on the companion's inbox: returns as soon as there is news (the owner speaking to it shows "
                        + "up as a <query>; world happenings as <event>s), or after up to " + MAX_WAIT_SECONDS
                        + " seconds with whatever accumulated. Events are consumed on read and nothing is "
                        + "lost between calls. Call it when you want to wait for the owner or for something to happen; "
                        + "events that arrive while you run programs come with the program's receipt.",
                getEventsSchema()));
        tools.add(toolDef("say",
                "Speak as the companion: the text appears in-game as its chat line, speech bubble, and voice "
                        + "(if one is bound). Use it to answer the owner's <query> events and to narrate what "
                        + "you are doing. Consecutive calls queue up and play in order.",
                saySchema()));

        // 全量给外脑,不做渐进披露:那是我们自己请求里的省法,外脑的上下文预算归它自己管,
        // 而且 MCP 客户端(Claude Code 之类)本来就会把借来的工具再延迟一次。
        for (NumenTool tool : ToolRegistry.all()) {
            if (config.isHidden(tool.name())) continue;
            tools.add(toolDef(tool.name(), tool.description(), withCompanion(tool.parameterSchema())));
        }

        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonObject toolDef(String name, String description, JsonObject inputSchema) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.add("inputSchema", inputSchema);
        return t;
    }

    /** An object schema with just an optional required {@code companion} string. */
    private JsonObject objectSchema(String companionKey, boolean required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        if (companionKey != null) props.add(companionKey, companionProp());
        schema.add("properties", props);
        if (companionKey != null && required) {
            JsonArray req = new JsonArray();
            req.add(companionKey);
            schema.add("required", req);
        }
        return schema;
    }

    /** An object schema with a single required string property (key + its description). */
    private JsonObject requiredStringSchema(String key, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "string");
        prop.addProperty("description", description);
        props.add(key, prop);
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        req.add(key);
        schema.add("required", req);
        return schema;
    }

    /** A Numen API tool's own schema, with a required {@code companion} argument injected. */
    private JsonObject withCompanion(java.util.Map<String, Object> parameterSchema) {
        JsonObject schema = parameterSchema == null
                ? new JsonObject()
                : gson.toJsonTree(parameterSchema).getAsJsonObject();
        schema.addProperty("type", "object");
        JsonObject props = schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        props.add("companion", companionProp());
        schema.add("properties", props);
        JsonArray required = schema.has("required") && schema.get("required").isJsonArray()
                ? schema.getAsJsonArray("required") : new JsonArray();
        boolean has = false;
        for (JsonElement e : required) if ("companion".equals(e.getAsString())) has = true;
        if (!has) required.add("companion");
        schema.add("required", required);
        return schema;
    }

    private JsonObject companionProp() {
        JsonObject p = new JsonObject();
        p.addProperty("type", "string");
        p.addProperty("description", "Which companion to act with — its name or id (see list_companions).");
        return p;
    }

    private JsonObject getEventsSchema() {
        JsonObject schema = objectSchema("companion", true);
        JsonObject wait = new JsonObject();
        wait.addProperty("type", "integer");
        wait.addProperty("description", "The longest to wait for news, in seconds (0–" + MAX_WAIT_SECONDS
                + ", default " + MAX_WAIT_SECONDS + "). 0 = return at once with whatever is queued.");
        schema.getAsJsonObject("properties").add("wait_seconds", wait);
        return schema;
    }

    private JsonObject saySchema() {
        JsonObject schema = objectSchema("companion", true);
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        text.addProperty("description", "What the companion says out loud.");
        schema.getAsJsonObject("properties").add("text", text);
        schema.getAsJsonArray("required").add("text");
        return schema;
    }

    // ---- tools/call ----

    /** 派发一次工具调用,并把它记进活动流——面板上那条流就是这里喂的。 */
    private JsonObject toolsCallResult(JsonObject req) {
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();

        return dispatchToolCall(name, args);
    }

    private JsonObject dispatchToolCall(String name, JsonObject args) {
        try {
            return switch (name) {
                case "list_companions" -> content(listCompanions(), false);
                case "create_companion" -> handleCreate(args);
                case "delete_companion" -> handleDelete(args);
                case "get_events" -> handleGetEvents(args);
                case "say" -> handleSay(args);
                default -> handleToolInvoke(name, args);
            };
        } catch (TimeoutException te) {
            return content("timed out waiting for the action to finish", true);
        } catch (Exception ex) {
            return content("call failed: " + ex.getMessage(), true);
        }
    }

    private String listCompanions() throws Exception {
        List<ExternalHands.Companion> list = hands.companions()
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (list.isEmpty()) {
            return "No companions are live in the world right now. Summon one in-game first.";
        }
        StringBuilder sb = new StringBuilder("Live companions:\n");
        for (ExternalHands.Companion c : list) {
            sb.append("- ").append(c.name()).append("  (id: ").append(c.uuid()).append(")\n");
        }
        return sb.toString().stripTrailing();
    }

    private JsonObject handleCreate(JsonObject args) throws Exception {
        String name = args.has("name") && !args.get("name").isJsonNull()
                ? args.get("name").getAsString().trim() : "";
        if (name.isEmpty()) {
            return content("create_companion needs a 'name' (3–16 letters, digits, or underscore)", true);
        }
        boolean ok = hands.create(name).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!ok) {
            return content("could not summon — is the game in a world?", true);
        }
        return content("summoning '" + name + "' — it arrives in a moment; call list_companions to confirm, "
                + "then drive it directly (no acquire needed)", false);
    }

    private JsonObject handleDelete(JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("no such companion — call list_companions to see valid names/ids", true);
        }
        boolean ok = hands.delete(target).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return content(ok ? "dismissed " + target + " — it drops its inventory, armor and accessories and is gone for good"
                : "could not dismiss " + target, !ok);
    }

    /** 长等待取件：停靠在收件箱上，会叫醒她的条目一到立即整批返回；到点清扫，
     *  有什么给什么（捎带的也交），没有就如实说没有。叫醒只是提前，正确性由到点清扫兜底。 */
    private JsonObject handleGetEvents(JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("get_events needs a 'companion' argument (a name or id from list_companions)", true);
        }
        int wait = MAX_WAIT_SECONDS;
        if (args.has("wait_seconds") && !args.get("wait_seconds").isJsonNull()) {
            wait = Math.max(0, Math.min(MAX_WAIT_SECONDS, args.get("wait_seconds").getAsInt()));
        }
        if (wait > 0) {
            ExternalWait.Parked parked = hands.awaitEvents(target);
            String batch = null;
            try {
                batch = parked.batch().get(wait, TimeUnit.SECONDS);
            } catch (TimeoutException deadline) {
                // 到点，转清扫——叫醒只是提前，正确性由清扫兜底
            } finally {
                // 撤销与叫醒谁先谁得：到点那一刻叫醒恰好取走的批次由撤销交回，取走的一定交到外脑手里
                String late = parked.cancel().get();
                if (batch == null) batch = late;
            }
            if (batch != null) return content(batch, false);
        }
        String rest = hands.takeEvents(target)
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return content(rest != null ? rest
                : "(no new events — call get_events again to keep waiting; the owner's words and world happenings land here)", false);
    }

    private JsonObject handleSay(JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("say needs a 'companion' argument (a name or id from list_companions)", true);
        }
        String text = args.has("text") && !args.get("text").isJsonNull()
                ? args.get("text").getAsString().trim() : "";
        if (text.isEmpty()) {
            return content("say needs a non-empty 'text'", true);
        }
        boolean ok = hands.say(target, text).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return content(ok ? "said" : "could not say — is the companion live?", !ok);
    }

    private JsonObject handleToolInvoke(String toolName, JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("this tool needs a 'companion' argument (a name or id from list_companions)", true);
        }
        JsonObject toolArgs = args.deepCopy();
        toolArgs.remove("companion");
        String result = hands.invoke(target, toolName, toolArgs.toString())
                .get(config.callTimeoutSeconds(), TimeUnit.SECONDS);
        // the external agent reads what the built-in brain reads: the result's message, never its structured data
        String events = hands.takeEvents(target).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return receipt(ToolOutcome.modelText(result), ToolOutcome.failed(result), events);
    }

    /**
     * 一次工具调用的回执：结果文本在前；这段时间到达的事件（{@link ExternalHands#takeEvents} 取走的那批，
     * 取走即不再由 get_events 交）作为另一段文本跟在后面，外脑干活时不必另外轮询。
     */
    static JsonObject receipt(String text, boolean isError, String events) {
        JsonObject out = content(text, isError);
        if (events != null) {
            out.getAsJsonArray("content").add(textItem(events));
        }
        return out;
    }

    /** Resolve the {@code companion} argument (name or UUID) to a live companion's UUID, or null. */
    private UUID resolveCompanion(JsonObject args) throws Exception {
        if (!args.has("companion") || args.get("companion").isJsonNull()) return null;
        String raw = args.get("companion").getAsString().trim();
        if (raw.isEmpty()) return null;
        List<ExternalHands.Companion> list = hands.companions()
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // exact id match first
        for (ExternalHands.Companion c : list) {
            if (c.uuid().toString().equalsIgnoreCase(raw)) return c.uuid();
        }
        // then exact name, then case-insensitive name
        for (ExternalHands.Companion c : list) {
            if (c.name().equals(raw)) return c.uuid();
        }
        for (ExternalHands.Companion c : list) {
            if (c.name().equalsIgnoreCase(raw)) return c.uuid();
        }
        return null;
    }

    // ---- result envelopes ----

    private static JsonObject textItem(String text) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        return item;
    }

    private static JsonObject content(String text, boolean isError) {
        JsonArray arr = new JsonArray();
        arr.add(textItem(text));
        JsonObject result = new JsonObject();
        result.add("content", arr);
        result.addProperty("isError", isError);
        return result;
    }

    private JsonObject okResponse(JsonElement id, JsonObject result) {
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id);
        r.add("result", result);
        return r;
    }

    private JsonObject errorResponse(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id == null ? JsonNull() : id);
        r.add("error", err);
        return r;
    }

    private static JsonElement JsonNull() {
        return com.google.gson.JsonNull.INSTANCE;
    }
}
