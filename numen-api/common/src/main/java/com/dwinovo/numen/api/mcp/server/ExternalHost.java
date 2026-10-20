package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ToolHints;
import com.dwinovo.numen.api.brain.Visits;
import com.dwinovo.numen.api.permission.ConsentAnswer;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.mcp.server.McpHost;
import com.dwinovo.numen.mcp.server.McpToken;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 外接大脑在游戏里的那一头:{@link McpHost} 的实现。MCP 端点(协议、传输、认人、限流、审计)在 {@code mcp} 模块,不认识同伴;
 * 这里把"有哪些工具、调一次给什么正文"落到同伴上。
 *
 * <p>大脑令牌看到:五个控制工具({@code list_companions}、{@code create_companion}、{@code delete_companion}、{@code get_events}、
 * {@code say}),加上她的工具表({@link ExternalHands#tools},与内置大脑每轮请求里发的是同一份,各带一个 {@code companion} 参数),
 * 调用路由到 {@link ExternalHands#invoke}:程序在她自己的派发器里跑,做完回执,正文见 {@link ExternalBrain}。没有"接管"握手——
 * 令牌连着,驾驶席就是它的({@link McpMode#driving})。渠道令牌看到四个渠道工具。
 */
public final class ExternalHost implements McpHost {

    private static final int MAX_WAIT_SECONDS = ExternalBrain.MAX_WAIT_SECONDS;
    private static final int CONTROL_TIMEOUT_SECONDS = ExternalBrain.CONTROL_TIMEOUT_SECONDS;

    // 控制工具的性质(判据见 ToolHints;lua 等她的工具各自在自己的定义处声明)。get_events / get_replies 取走的是递给她的话与
    // 事件,不改世界里的任何东西,对客户端是只读(可以放心自动放行);取走即不再给,所以不幂等。
    private static final ToolHints LIST_COMPANIONS = ToolHints.reads("List companions");
    private static final ToolHints CREATE_COMPANION = new ToolHints("Summon companion", false, false, true, false);
    private static final ToolHints DELETE_COMPANION = new ToolHints("Dismiss companion", false, true, true, false);
    private static final ToolHints GET_EVENTS = new ToolHints("Wait for events", true, false, false, false);
    private static final ToolHints GUIDE = ToolHints.reads("Read the guide");
    private static final ToolHints SAY = new ToolHints("Say aloud", false, false, false, false);
    private static final ToolHints SEND = new ToolHints("Send the owner's words", false, false, false, false);
    private static final ToolHints GET_REPLIES = new ToolHints("Wait for replies", true, false, false, false);
    private static final ToolHints ANSWER_CONSENT = new ToolHints("Answer consent", false, false, true, false);

    private static final String ICON = "/numen_api_mcp_icon.png";

    private final Function<UUID, ExternalHands> handsOf;
    private final McpChannels channels;
    private final String version;
    private final Gson gson = new Gson();
    private final byte[] icon = loadIcon();

    /**
     * @param version Numen API 这个模组的版本,serverInfo 里报
     */
    public ExternalHost(Function<UUID, ExternalHands> handsOf, McpChannels channels, String version) {
        this.handsOf = handsOf;
        this.channels = channels;
        this.version = version;
    }

    private static byte[] loadIcon() {
        try (InputStream in = ExternalHost.class.getResourceAsStream(ICON)) {
            if (in == null) {
                throw new IllegalStateException(ICON + " is not in the jar");
            }
            return in.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    @Override
    public Identity identity() {
        return new Identity("numen-mcp", "Numen",
                "Drive Numen's Minecraft companions through the same API their built-in brain uses.", version, icon);
    }

    @Override
    public String instructions() {
        return McpInstructions.build();
    }

    // ---- 工具表 ----

    @Override
    public List<ToolInfo> tools(McpToken who) throws Exception {
        List<ToolInfo> tools = new ArrayList<>();
        if (who.role() == McpToken.Role.CHANNEL) {
            channelTools(tools);
            return tools;
        }
        tools.add(def("list_companions", LIST_COMPANIONS,
                "List the live Minecraft companions this token may drive (name + id). Call this first to see who you can drive.",
                objectSchema(null, false)));
        tools.add(def("create_companion", CREATE_COMPANION,
                "Summon a new companion into the world by name (3–16 letters, digits, or underscore). It arrives "
                        + "in a moment; call list_companions to confirm, then drive it directly. "
                        + "Needs a token that covers all of the owner's companions, and the owner in the game.",
                requiredStringSchema("name", "The new companion's name (3–16 letters, digits, or underscore).")));
        tools.add(def("delete_companion", DELETE_COMPANION,
                "Permanently dismiss a companion — it drops its inventory, armor and accessories and is gone for good. "
                        + "Takes its name or id. Needs the owner in the game.",
                requiredStringSchema("companion", "Which companion to dismiss — its name or id (see list_companions).")));
        tools.add(def("get_events", GET_EVENTS,
                "Wait on the companion's inbox: returns as soon as there is news (the owner speaking to it shows "
                        + "up as a <query>; world happenings as <event>s), or after up to " + MAX_WAIT_SECONDS
                        + " seconds with whatever accumulated. Events are consumed on read and nothing is "
                        + "lost between calls. The result ends with her <runtime_state>. Call it when you want to wait "
                        + "for the owner or for something to happen; events that arrive while you run programs come "
                        + "with the program's receipt.",
                getEventsSchema()));
        tools.add(def("guide", GUIDE,
                "Read the guide the companion's own brain starts every request with: its persona, how its body gets "
                        + "things done, the API index, its skills, its instincts and the rules for notes and for speaking. "
                        + "Read it once, before you begin, and keep it; it is long.",
                objectSchema("companion", true)));
        tools.add(def("say", SAY,
                "Speak as the companion: the text appears in-game as its chat line, speech bubble, and voice "
                        + "(if one is bound). Use it to answer the owner's <query> events and to narrate what "
                        + "you are doing. Consecutive calls queue up and play in order.",
                saySchema()));
        // 全量给外脑,不做渐进披露:那是我们自己请求里的省法,外脑的上下文预算归它自己管,
        // 而且 MCP 客户端(Claude Code 之类)本来就会把借来的工具再延迟一次。工具表是内置大脑每轮请求里发的那一份;
        // 顺序是确定的:控制工具固定在前,其余按名字排——接进来的 MCP 工具什么时候登记不由我们定。
        handsOf.apply(who.owner()).tools().get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                .sorted(Comparator.comparing(NumenTool::name))
                .forEach(tool -> tools.add(def(tool.name(), tool.hints(), tool.description(),
                        withCompanion(tool.parameterSchema()))));
        return tools;
    }

    /** 渠道看到的四个工具:点名、把主人的话送进收件箱、收她的回话与征询、答征询。 */
    private void channelTools(List<ToolInfo> tools) {
        tools.add(def("list_companions", LIST_COMPANIONS,
                "List the live companions this token may speak to (name + id).", objectSchema(null, false)));
        JsonObject send = objectSchema("companion", true);
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        text.addProperty("description", "What the owner said, as the owner said it.");
        send.getAsJsonObject("properties").add("text", text);
        send.getAsJsonArray("required").add("text");
        tools.add(def("send", SEND,
                "Hand the owner's words to a companion, from this channel. Her own brain thinks about it (with the "
                        + "owner's model key) and her answer and any question for the owner come back through "
                        + "get_replies. When the round is done and the owner is not in the game, she goes back to "
                        + "standing by.", send));
        JsonObject wait = new JsonObject();
        wait.addProperty("type", "integer");
        wait.addProperty("description", "The longest to wait, in seconds (0–" + MAX_WAIT_SECONDS
                + ", default " + MAX_WAIT_SECONDS + "). 0 = return at once.");
        JsonObject replies = objectSchema(null, false);
        replies.getAsJsonObject("properties").add("wait_seconds", wait);
        tools.add(def("get_replies", GET_REPLIES,
                "Wait for what her brain has for this channel: {\"items\":[...]} where an item is "
                        + "{\"type\":\"said\",\"companion\",\"text\"}, {\"type\":\"consent\",\"id\",\"companion\","
                        + "\"name\",\"doing\",\"remember\",\"choices\"} (a question for the owner: show it, then call "
                        + "answer_consent) or {\"type\":\"consent_withdrawn\",\"id\"} (no need to answer any more). "
                        + "Returns as soon as something is there, or after the wait with an empty list.", replies));
        JsonObject answer = objectSchema("companion", true);
        JsonObject id = new JsonObject();
        id.addProperty("type", "integer");
        id.addProperty("description", "The id of the consent item.");
        JsonObject decision = new JsonObject();
        decision.addProperty("type", "string");
        JsonArray choices = new JsonArray();
        choices.add("allow");
        choices.add("remember");
        choices.add("deny");
        decision.add("enum", choices);
        JsonObject note = new JsonObject();
        note.addProperty("type", "string");
        note.addProperty("description", "Only with deny: what the owner wants her to do instead.");
        JsonObject props = answer.getAsJsonObject("properties");
        props.add("id", id);
        props.add("decision", decision);
        props.add("note", note);
        answer.getAsJsonArray("required").add("id");
        answer.getAsJsonArray("required").add("decision");
        tools.add(def("answer_consent", ANSWER_CONSENT,
                "Pass on the owner's answer to a consent item from get_replies, exactly as he gave it. Only the "
                        + "owner's own choice counts here; do not answer for him.", answer));
    }

    private static ToolInfo def(String name, ToolHints hints, String description, JsonObject schema) {
        return new ToolInfo(name, hints.title(), description, schema.toString(),
                new Hints(hints.readOnly(), hints.destructive(), hints.idempotent(), hints.openWorld()));
    }

    /** An object schema with just an optional required {@code companion} string. */
    private static JsonObject objectSchema(String companionKey, boolean required) {
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
    private static JsonObject requiredStringSchema(String key, String description) {
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
    private JsonObject withCompanion(Map<String, Object> parameterSchema) {
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

    private static JsonObject companionProp() {
        JsonObject p = new JsonObject();
        p.addProperty("type", "string");
        p.addProperty("description", "Which companion to act with — its name or id (see list_companions).");
        return p;
    }

    private static JsonObject getEventsSchema() {
        JsonObject schema = objectSchema("companion", true);
        JsonObject wait = new JsonObject();
        wait.addProperty("type", "integer");
        wait.addProperty("description", "The longest to wait for news, in seconds (0–" + MAX_WAIT_SECONDS
                + ", default " + MAX_WAIT_SECONDS + "). 0 = return at once with whatever is queued.");
        schema.getAsJsonObject("properties").add("wait_seconds", wait);
        return schema;
    }

    private static JsonObject saySchema() {
        JsonObject schema = objectSchema("companion", true);
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        text.addProperty("description", "What the companion says out loud.");
        schema.getAsJsonObject("properties").add("text", text);
        schema.getAsJsonArray("required").add("text");
        return schema;
    }

    // ---- 调用 ----

    @Override
    public Result call(McpToken who, String tool, String argsJson, int timeoutSeconds, Progress progress)
            throws Exception {
        JsonObject args = JsonParser.parseString(argsJson).getAsJsonObject();
        Call call = new Call(who, handsOf.apply(who.owner()), channels, args, timeoutSeconds, progress);
        if (who.role() == McpToken.Role.CHANNEL) {
            return switch (tool) {
                case "list_companions" -> call.listCompanions();
                case "send" -> call.send(channels.channel(who.id()));
                case "get_replies" -> call.getReplies(who.id());
                case "answer_consent" -> call.answerConsent();
                default -> call.text("a channel token can only use list_companions, send, get_replies and "
                        + "answer_consent", true);
            };
        }
        return switch (tool) {
            case "list_companions" -> call.listCompanions();
            case "create_companion" -> call.create();
            case "delete_companion" -> call.delete();
            case "get_events" -> call.getEvents();
            case "guide" -> call.guide();
            case "say" -> call.say();
            default -> call.invoke(tool);
        };
    }

    /** 一次调用:这张令牌、这位主人的手;解析到的同伴作为结果的作用对象给审计。 */
    private static final class Call {
        private final McpToken token;
        private final ExternalHands hands;
        private final McpChannels channels;
        private final JsonObject args;
        private final int timeoutSeconds;
        private final Progress progress;
        private UUID target;

        Call(McpToken token, ExternalHands hands, McpChannels channels, JsonObject args, int timeoutSeconds,
             Progress progress) {
            this.token = token;
            this.hands = hands;
            this.channels = channels;
            this.args = args;
            this.timeoutSeconds = timeoutSeconds;
            this.progress = progress;
        }

        Result text(String text, boolean error) {
            return new Result(List.of(text), error, target);
        }

        /** 作用域管得着的、此刻在世界里的同伴。 */
        private List<ExternalHands.Companion> visible() throws Exception {
            return hands.companions().get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                    .filter(c -> token.scope().covers(c.uuid())).toList();
        }

        Result listCompanions() throws Exception {
            List<ExternalHands.Companion> list = visible();
            if (list.isEmpty()) {
                return text("No companions this token may drive are live in the world right now.", false);
            }
            StringBuilder sb = new StringBuilder("Live companions:\n");
            for (ExternalHands.Companion c : list) {
                sb.append("- ").append(c.name()).append("  (id: ").append(c.uuid()).append(")\n");
            }
            return text(sb.toString().stripTrailing(), false);
        }

        Result create() throws Exception {
            if (!token.scope().all()) {
                return text("this token is limited to named companions; it cannot summon new ones", true);
            }
            String name = string("name").trim();
            if (name.isEmpty()) {
                return text("create_companion needs a 'name' (3–16 letters, digits, or underscore)", true);
            }
            boolean ok = hands.create(name).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!ok) {
                return text("could not summon — the owner has to be in the game", true);
            }
            return text("summoning '" + name + "' — it arrives in a moment; call list_companions to confirm, "
                    + "then drive it directly", false);
        }

        Result delete() throws Exception {
            UUID companion = resolve();
            if (companion == null) {
                return text("no such companion — call list_companions to see valid names/ids", true);
            }
            boolean ok = hands.delete(companion).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return text(ok ? "dismissed " + companion + " — it drops its inventory, armor and accessories and is gone for good"
                    : "could not dismiss " + companion + " — the owner has to be in the game", !ok);
        }

        /** 等她的消息,正文见 {@link ExternalBrain#events}。 */
        Result getEvents() throws Exception {
            UUID companion = resolve();
            if (companion == null) {
                return text("get_events needs a 'companion' argument (a name or id from list_companions)", true);
            }
            return body(ExternalBrain.events(hands, companion, waitSeconds()));
        }

        /** 渠道:把主人的话送给她。 */
        Result send(Visits.Channel channel) throws Exception {
            UUID companion = resolve();
            String words = string("text").trim();
            if (companion == null || words.isEmpty()) {
                return text("send needs a 'companion' (name or id from list_companions) and a non-empty 'text'", true);
            }
            boolean ok = hands.hear(companion, words, channel).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return text(ok ? "sent — her answer comes back through get_replies" : "could not send to her", !ok);
        }

        /** 渠道:收她的回话与征询。 */
        Result getReplies(String tokenId) throws Exception {
            JsonArray items = new JsonArray();
            channels.take(tokenId, waitSeconds() * 1000L).forEach(items::add);
            JsonObject out = new JsonObject();
            out.add("items", items);
            return text(out.toString(), false);
        }

        /** 渠道:把主人的选择交给征询台,和游戏里答复框同一个入口。 */
        Result answerConsent() throws Exception {
            UUID companion = resolve();
            if (companion == null || !args.has("id") || !args.has("decision")) {
                return text("answer_consent needs 'companion', 'id' and 'decision' (allow, remember or deny)", true);
            }
            ConsentAnswer.Decision decision = switch (args.get("decision").getAsString()) {
                case "allow" -> ConsentAnswer.Decision.ALLOW_ONCE;
                case "remember" -> ConsentAnswer.Decision.ALLOW_REMEMBER;
                case "deny" -> ConsentAnswer.Decision.DENY;
                default -> null;
            };
            String note = string("note");
            if (decision == null || (decision != ConsentAnswer.Decision.DENY && !note.isBlank())) {
                return text("'decision' is allow, remember or deny; a note only goes with deny", true);
            }
            ConsentDesk.Reply reply = hands.answer(companion, args.get("id").getAsLong(), decision, note)
                    .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return text(reply == ConsentDesk.Reply.ANSWERED ? "answered"
                    : "nothing to answer: that request is not pending any more (answered, expired or replaced)",
                    reply != ConsentDesk.Reply.ANSWERED);
        }

        Result guide() throws Exception {
            UUID companion = resolve();
            if (companion == null) {
                return text("guide needs a 'companion' argument (a name or id from list_companions)", true);
            }
            return body(ExternalBrain.guide(hands, companion));
        }

        Result say() throws Exception {
            UUID companion = resolve();
            if (companion == null) {
                return text("say needs a 'companion' argument (a name or id from list_companions)", true);
            }
            String words = string("text").trim();
            if (words.isEmpty()) {
                return text("say needs a non-empty 'text'", true);
            }
            boolean ok = hands.say(companion, words).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return text(ok ? "said" : "could not say — is the companion live?", !ok);
        }

        /** 跑她的一个工具(程序、技能、计划、札记……),回执见 {@link ExternalBrain#afterProgram}。 */
        Result invoke(String toolName) throws Exception {
            UUID companion = resolve();
            if (companion == null) {
                return text("this tool needs a 'companion' argument (a name or id from list_companions)", true);
            }
            JsonObject toolArgs = args.deepCopy();
            toolArgs.remove("companion");
            String result = hands.invoke(companion, toolName, toolArgs.toString(), progress::report)
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            return body(ExternalBrain.afterProgram(hands, companion, result));
        }

        private Result body(ExternalBrain.Body body) {
            return new Result(body.texts(), body.failed(), target);
        }

        private int waitSeconds() {
            int wait = MAX_WAIT_SECONDS;
            if (args.has("wait_seconds") && !args.get("wait_seconds").isJsonNull()) {
                wait = Math.max(0, Math.min(MAX_WAIT_SECONDS, args.get("wait_seconds").getAsInt()));
            }
            return wait;
        }

        private String string(String key) {
            return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
        }

        /** 把 {@code companion} 参数(名字或 UUID)解析成这张令牌管得着的、活着的同伴;没有是 null。 */
        private UUID resolve() throws Exception {
            String raw = string("companion").trim();
            if (raw.isEmpty()) return null;
            List<ExternalHands.Companion> list = visible();
            for (ExternalHands.Companion c : list) {
                if (c.uuid().toString().equalsIgnoreCase(raw)) return found(c);
            }
            for (ExternalHands.Companion c : list) {
                if (c.name().equals(raw)) return found(c);
            }
            for (ExternalHands.Companion c : list) {
                if (c.name().equalsIgnoreCase(raw)) return found(c);
            }
            return null;
        }

        private UUID found(ExternalHands.Companion c) {
            target = c.uuid();
            return c.uuid();
        }
    }
}
