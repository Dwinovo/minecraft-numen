package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.agent.provider.AnthropicProvider;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话记录的一行,以及行与消息({@link ConvoState.Msg})之间的换法——全仓只有这一处认行的写法。记录的格式照 Claude Code 的会话记录:
 * 一行一条 JSON,没有文件头,只追加;每行有 {@code type}、{@code uuid}、{@code parentUuid}、{@code timestamp}、{@code version}。
 *
 * <h2>行</h2>
 * <ul>
 *   <li>{@code user}:{@code message.content} 是字符串(有人说话;元信息带 {@code isMeta});或是一个 {@code tool_result} 块
 *       (工具结果,行上带 {@code sourceToolAssistantUUID});内容是 {@link #INTERRUPTED} 的一行是主人叫停了这一轮;
 *       {@code isCompactSummary} 的一行是整理的摘要。{@code from} 是说话的人({@code player@短码}),一行只有一个人的话。</li>
 *   <li>{@code assistant}:{@code message} 照 Anthropic 的消息({@code id}、{@code model}、{@code stop_reason}、{@code content}),
 *       一个块({@code thinking}、{@code text}、{@code tool_use})一行,同一条回复共用 {@code message.id}。
 *       {@code isApiErrorMessage} 的一行是这一轮没能完成(模型调用失败、死亡、登出……),说明在文字块里,{@code code} 与
 *       {@code args} 是面板翻成玩家语言的结构化原因。</li>
 *   <li>{@code attachment}:往后追加的状态与提醒,{@code attachment.type} 分种类({@link #GOAL_STATUS}、{@link #PERSONA_CHANGED})。</li>
 *   <li>{@code system}:{@code subtype: compact_boundary} 是整理的边界({@code parentUuid} 为空,{@code logicalParentUuid} 指回整理前最后一行,
 *       {@code compactMetadata} 记触发方式与前后用量与原文保留的那一段),紧跟着的是摘要。</li>
 *   <li>每行另有 {@code conv}:这一行属于哪个群(单聊没有)。认不得的 {@code type} 读时跳过,留在文件里;插件自己的记录
 *       {@code <命名空间>:<名字>} 也是,它不进上下文也不进面板。</li>
 * </ul>
 * 助手回合里属于某一家服务商的私货({@link AssistantTurn#extras}、{@link AssistantTurn#origin})随回合的第一行带着
 * ({@code extras}、{@code origin}),思考签名在思考块的 {@code signature} 里;换了协议的服务商在发出前才转线格式。
 *
 * <h2>两个视图</h2>
 * {@link #replay} 是模型的上下文:摘要与原文保留的近段、整理之后的行,按这个顺序重放;{@link #lines} 是面板看到的物理记录:
 * 整理与换人设画成分隔记号,元信息与状态不显示。
 *
 * <p>纯 JVM。
 */
public final class Rows {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
    public static final String ATTACHMENT = "attachment";
    public static final String SYSTEM = "system";
    public static final String COMPACT_BOUNDARY = "compact_boundary";

    /** 目标的状态:{@code condition}、{@code met}、{@code reason},设定与清除的两条带 {@code sentinel: true}。 */
    public static final String GOAL_STATUS = "goal_status";
    /** 这里换过人设(只记何时,不记换成了什么:绑定的真源是 {@code binding.json})。 */
    public static final String PERSONA_CHANGED = "persona_changed";

    /** 主人叫停一轮时那一行的文字,照 Claude Code。 */
    public static final String INTERRUPTED = "[Request interrupted by user]";
    /** 叫停这一轮的原因码与给模型的话:{@link #INTERRUPTED} 这一行读回来就是它。 */
    public static final String OWNER_STOP_CODE = "halt.owner_stop";
    public static final String OWNER_STOP_WORDS = "interrupted by the owner";

    /** 整理的分隔记号(面板画成一条细线,不发给模型)。 */
    public static final String COMPACT_DIVIDER = "[numen:compact-divider]";
    /** 换人设的分隔记号(面板画成"人设已切换",不发给模型)。 */
    public static final String PERSONA_DIVIDER = "[numen:persona-divider]";

    private static final String SUMMARY_MODEL = "<synthetic>";

    private Rows() {}

    /**
     * 这一行在哪儿写下的。
     *
     * @param conv 群的会话印;单聊是 {@code null}
     */
    public record Stamp(String conv) {
        public static final Stamp NONE = new Stamp(null);
    }

    /** 面板记录的一行:消息本身,加上它是什么时候记的、记在哪个会话印名下、由哪几行(物理上的)组成。 */
    public record Line(String id, long ts, String conv, ConvoState.Msg msg, List<JsonObject> rows) {}

    /** 上下文里一条消息占的行:第一行与最后一行的 uuid。整理时原文保留的那一段按它指。 */
    public record Span(String first, String last) {}

    /**
     * 重放一个会话的结果。
     *
     * @param context 模型的上下文
     * @param spans   上下文里每条消息占的行,与 {@code context} 一一对应
     * @param last    这个会话里物理上最后一行的 uuid;一行都没有是 {@code null}
     * @param tools   每个工具调用的 id 对应它所在的助手行的 uuid
     */
    public record Replay(List<ConvoState.Msg> context, List<Span> spans, String last, Map<String, String> tools) {}

    // ---- 写 ----

    /**
     * 一个会话的写手:给每一行盖 uuid、时间与版本,把它们串成链,并记着上下文里每条消息占哪几行(整理时据此指出原文保留的那一段)。
     * 每个会话一个;换会话({@link #restart})或接着已有的会话写({@link #adopt})。
     *
     * <p>线程:调用方保证同一时刻只有一个线程写。
     */
    public static final class Writer {

        private final String version;
        private String last;
        private final List<Span> spans = new ArrayList<>();
        private final Map<String, String> tools = new HashMap<>();

        /** @param version 写下这些行的 Numen 的版本 */
        public Writer(String version) {
            this.version = version;
        }

        /** 接着一个已有的会话写:链接在它最后一行后面,上下文的账从重放的结果来。 */
        public void adopt(Replay replay) {
            last = replay.last();
            spans.clear();
            spans.addAll(replay.spans());
            tools.clear();
            tools.putAll(replay.tools());
        }

        /** 新会话:从空白起。 */
        public void restart() {
            last = null;
            spans.clear();
            tools.clear();
        }

        /** 一条消息写成的行(一条助手回复是好几行);它进上下文。 */
        public List<JsonObject> write(ConvoState.Msg msg, Stamp stamp) {
            List<JsonObject> out = switch (msg) {
                case ConvoState.Msg.User u -> List.of(userRow(u, stamp));
                case ConvoState.Msg.Tool t -> List.of(toolRow(t, stamp));
                case ConvoState.Msg.Assistant a -> assistantRows(a.turn(), stamp);
                case ConvoState.Msg.Halt h -> List.of(haltRow(h, stamp));
            };
            spans.add(new Span(out.getFirst().get("uuid").getAsString(), out.getLast().get("uuid").getAsString()));
            return out;
        }

        /** 一条状态或提醒({@code attachment}):不进上下文。 */
        public JsonObject attach(String type, JsonObject fields, Stamp stamp) {
            JsonObject attachment = fields.deepCopy();
            attachment.addProperty("type", type);
            JsonObject row = row(ATTACHMENT, stamp);
            row.add("attachment", attachment);
            return row;
        }

        /**
         * 一次整理:边界与摘要两行。上下文里最后 {@code keptTail} 条消息原文保留,边界的 {@code preservedSegment} 指着它们
         * ——这几条还是它们原来的行,不抄第二份。
         *
         * @param meta 触发方式、前后用量、耗时,只用于记账,重放不读
         * @throws IllegalArgumentException 上下文里没有这么多条
         */
        public List<JsonObject> compact(String summary, int keptTail, JsonObject meta, Stamp stamp) {
            if (keptTail < 0 || keptTail > spans.size()) {
                throw new IllegalArgumentException("asked to keep the last " + keptTail + " but the context holds only "
                        + spans.size());
            }
            JsonObject metadata = meta == null ? new JsonObject() : meta.deepCopy();
            List<Span> kept = new ArrayList<>(spans.subList(spans.size() - keptTail, spans.size()));
            if (!kept.isEmpty()) {
                JsonObject segment = new JsonObject();
                segment.addProperty("headUuid", kept.getFirst().first());
                segment.addProperty("tailUuid", kept.getLast().last());
                metadata.add("preservedSegment", segment);
            }
            String before = last;
            JsonObject boundary = row(SYSTEM, stamp);
            boundary.addProperty("subtype", COMPACT_BOUNDARY);
            boundary.addProperty("content", "Conversation compacted");
            boundary.add("parentUuid", com.google.gson.JsonNull.INSTANCE);
            if (before != null) {
                boundary.addProperty("logicalParentUuid", before);
            }
            boundary.add("compactMetadata", metadata);
            JsonObject summaryRow = row(USER, stamp);
            summaryRow.addProperty("isCompactSummary", true);
            summaryRow.add("message", userMessage(summary));
            Span summarySpan = new Span(summaryRow.get("uuid").getAsString(), summaryRow.get("uuid").getAsString());
            spans.clear();
            spans.add(summarySpan);
            spans.addAll(kept);
            return List.of(boundary, summaryRow);
        }

        private JsonObject row(String type, Stamp stamp) {
            JsonObject row = new JsonObject();
            String uuid = UUID.randomUUID().toString();
            row.addProperty("type", type);
            row.addProperty("uuid", uuid);
            row.addProperty("parentUuid", last);
            row.addProperty("timestamp", Instant.now().toString());
            row.addProperty("version", version);
            if (stamp.conv() != null && !stamp.conv().isBlank()) {
                row.addProperty("conv", stamp.conv());
            }
            last = uuid;
            return row;
        }

        private JsonObject userRow(ConvoState.Msg.User user, Stamp stamp) {
            JsonObject row = row(USER, stamp);
            if (user.meta()) {
                row.addProperty("isMeta", true);
            }
            if (user.from() != null && !user.from().isBlank()) {
                row.addProperty("from", user.from());
            }
            row.add("message", userMessage(user.content()));
            return row;
        }

        private JsonObject toolRow(ConvoState.Msg.Tool tool, Stamp stamp) {
            JsonObject row = row(USER, stamp);
            JsonObject block = new JsonObject();
            block.addProperty("type", "tool_result");
            block.addProperty("tool_use_id", tool.toolCallId());
            block.addProperty("content", tool.content());
            JsonArray content = new JsonArray();
            content.add(block);
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.add("content", content);
            row.add("message", message);
            String source = tools.get(tool.toolCallId());
            if (source != null) {
                row.addProperty("sourceToolAssistantUUID", source);
            }
            return row;
        }

        private List<JsonObject> assistantRows(AssistantTurn turn, Stamp stamp) {
            String id = "msg_" + UUID.randomUUID().toString().replace("-", "");
            JsonObject extras = turn.extras().deepCopy();
            List<JsonObject> blocks = new ArrayList<>();
            if (turn.hasReasoning()) {
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "thinking");
                thinking.addProperty("thinking", turn.reasoning());
                JsonElement signature = extras.remove(AnthropicProvider.SIGNATURE_KEY);
                if (signature != null) {
                    thinking.add("signature", signature);
                }
                blocks.add(thinking);
            }
            if (!turn.content().isEmpty()) {
                blocks.add(textBlock(turn.content()));
            }
            for (LlmToolCall call : turn.toolCalls()) {
                JsonObject use = new JsonObject();
                use.addProperty("type", "tool_use");
                use.addProperty("id", call.id());
                use.addProperty("name", call.name());
                use.add("input", JsonParser.parseString(call.arguments()));
                blocks.add(use);
            }
            if (blocks.isEmpty()) {
                blocks.add(textBlock(""));
            }
            List<JsonObject> rows = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                JsonObject row = row(ASSISTANT, stamp);
                JsonObject message = new JsonObject();
                message.addProperty("id", id);
                message.addProperty("role", ASSISTANT);
                if (turn.origin() != null) {
                    message.addProperty("model", modelOf(turn.origin()));
                }
                message.addProperty("stop_reason", turn.hasToolCalls() ? "tool_use" : "end_turn");
                JsonArray content = new JsonArray();
                content.add(blocks.get(i));
                message.add("content", content);
                row.add("message", message);
                if (i == 0) {
                    if (turn.origin() != null) {
                        row.addProperty("origin", turn.origin());
                    }
                    if (!extras.entrySet().isEmpty()) {
                        row.add("extras", extras);
                    }
                }
                if (blocks.get(i).get("type").getAsString().equals("tool_use")) {
                    tools.put(blocks.get(i).get("id").getAsString(), row.get("uuid").getAsString());
                }
                rows.add(row);
            }
            return rows;
        }

        private JsonObject haltRow(ConvoState.Msg.Halt halt, Stamp stamp) {
            if (OWNER_STOP_CODE.equals(halt.code())) {
                JsonObject row = row(USER, stamp);
                row.add("message", userMessage(INTERRUPTED));
                return row;
            }
            JsonObject row = row(ASSISTANT, stamp);
            row.addProperty("isApiErrorMessage", true);
            if (halt.code() != null) {
                row.addProperty("code", halt.code());
                JsonArray args = new JsonArray();
                halt.args().forEach(args::add);
                row.add("args", args);
            }
            JsonObject message = new JsonObject();
            message.addProperty("id", "msg_" + UUID.randomUUID().toString().replace("-", ""));
            message.addProperty("role", ASSISTANT);
            message.addProperty("model", SUMMARY_MODEL);
            message.addProperty("stop_reason", "stop_sequence");
            JsonArray content = new JsonArray();
            content.add(textBlock(halt.reason()));
            message.add("content", content);
            row.add("message", message);
            return row;
        }

        private static JsonObject textBlock(String text) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", text);
            return block;
        }

        private static JsonObject userMessage(String content) {
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", content);
            return message;
        }
    }

    /** 出处戳 {@code 协议/厂家/型号} 里的型号:型号自己可以带 {@code /}。 */
    private static String modelOf(String origin) {
        int first = origin.indexOf('/');
        int second = first < 0 ? -1 : origin.indexOf('/', first + 1);
        return second < 0 ? origin : origin.substring(second + 1);
    }

    // ---- 读 ----

    /**
     * 打开一个会话:模型的上下文要读哪些行,按重放的顺序。
     *
     * @param rows 重放顺序的行;没有整理过就是整份记录。整理过的,是最后一个边界起的那些(边界、摘要、整理之后写下的),
     *             摘要之后紧接着是原文保留的那一段(它们在记录里排在边界前面,边界的 {@code preservedSegment} 指着)
     * @param last 这个会话物理上最后一行的 uuid;一行都没有是 {@code null}
     * @param goal 当前目标从设定起的 {@code goal_status} 行,按先后;没有目标是空表(目标不受整理影响,文件从不删改)
     */
    public record Opening(List<JsonObject> rows, String last, List<JsonObject> goal) {}

    /** 一个还在的目标:条件、设定的时刻、已经判过几轮(设定算第一轮)、评估员最近的理由。 */
    public record Goal(String condition, long since, int rounds, String reason) {}

    /** {@link Opening#goal} 读成目标;没有目标是 {@code null}。 */
    public static Goal goal(List<JsonObject> goalRows) {
        if (goalRows.isEmpty()) {
            return null;
        }
        JsonObject set = goalRows.getFirst().getAsJsonObject("attachment");
        JsonObject last = goalRows.getLast().getAsJsonObject("attachment");
        return new Goal(string(set, "condition"), millis(goalRows.getFirst()), goalRows.size(),
                last.has("reason") ? string(last, "reason") : null);
    }

    /** 从整份记录(物理顺序)算出打开这个会话要给脑子的东西。 */
    public static Opening open(List<JsonObject> all) {
        int boundary = -1;
        for (int i = 0; i < all.size(); i++) {
            if (SYSTEM.equals(string(all.get(i), "type")) && COMPACT_BOUNDARY.equals(string(all.get(i), "subtype"))) {
                boundary = i;
            }
        }
        List<JsonObject> rows = new ArrayList<>();
        if (boundary < 0) {
            rows.addAll(all);
        } else {
            rows.add(all.get(boundary));
            JsonObject segment = all.get(boundary).get("compactMetadata") instanceof JsonObject m
                    && m.get("preservedSegment") instanceof JsonObject p ? p : null;
            List<JsonObject> after = all.subList(boundary + 1, all.size());
            int summary = 0;
            while (summary < after.size() && !bool(after.get(summary), "isCompactSummary")) {
                summary++;
            }
            if (summary < after.size()) {
                rows.add(after.get(summary));
            }
            if (segment != null) {
                boolean inside = false;
                for (JsonObject row : all.subList(0, boundary)) {
                    inside |= string(row, "uuid").equals(string(segment, "headUuid"));
                    if (inside) {
                        rows.add(row);
                    }
                    if (string(row, "uuid").equals(string(segment, "tailUuid"))) {
                        break;
                    }
                }
            }
            for (int i = 0; i < after.size(); i++) {
                if (i != summary) {
                    rows.add(after.get(i));
                }
            }
        }
        List<JsonObject> goal = new ArrayList<>();
        for (JsonObject row : all) {
            if (!ATTACHMENT.equals(string(row, "type")) || !(row.get("attachment") instanceof JsonObject a)
                    || !GOAL_STATUS.equals(string(a, "type"))) {
                continue;
            }
            if (bool(a, "met")) {
                goal.clear();
            } else if (bool(a, "sentinel")) {
                goal.clear();
                goal.add(row);
            } else if (!goal.isEmpty()) {
                goal.add(row);
            }
        }
        return new Opening(List.copyOf(rows), all.isEmpty() ? null : string(all.getLast(), "uuid"), List.copyOf(goal));
    }

    /** 读出来的一项:一条消息(或一个记号)和它占的行。 */
    private enum Role {
        /** 对话本身:进上下文,面板也画。 */
        TALK,
        /** 进上下文,面板不画:元信息、整理的摘要。 */
        CONTEXT_ONLY,
        /** 面板画成分隔记号,不进上下文。 */
        DIVIDER,
        /** 都不进:状态、边界、认不得的行。 */
        NEITHER
    }

    private record Item(Role role, ConvoState.Msg msg, List<JsonObject> rows) {}

    /**
     * 按给定的顺序重放一个会话的行,得到模型的上下文。顺序由读它的一方({@code 服务端的 open})排好:整理的摘要在前,
     * 接着原文保留的那一段,再接整理之后写下的行。
     *
     * @param last 这个会话物理上最后一行的 uuid
     */
    public static Replay replay(List<JsonObject> rows, String last) {
        List<ConvoState.Msg> context = new ArrayList<>();
        List<Span> spans = new ArrayList<>();
        Map<String, String> tools = new HashMap<>();
        for (Item item : parse(rows)) {
            for (JsonObject row : item.rows()) {
                for (JsonElement block : contentBlocks(row)) {
                    if (block.isJsonObject() && "tool_use".equals(string(block.getAsJsonObject(), "type"))) {
                        tools.put(string(block.getAsJsonObject(), "id"), string(row, "uuid"));
                    }
                }
            }
            if (item.role() == Role.TALK || item.role() == Role.CONTEXT_ONLY) {
                context.add(item.msg());
                spans.add(new Span(string(item.rows().getFirst(), "uuid"), string(item.rows().getLast(), "uuid")));
            }
        }
        return new Replay(List.copyOf(context), List.copyOf(spans), last, Map.copyOf(tools));
    }

    /** 面板的视图:物理顺序,整理与换人设画成分隔记号,元信息与状态不显示。 */
    public static List<Line> lines(List<JsonObject> rows) {
        List<Line> out = new ArrayList<>();
        for (Item item : parse(rows)) {
            ConvoState.Msg shown = switch (item.role()) {
                case TALK -> item.msg();
                case DIVIDER -> item.msg();
                default -> null;
            };
            if (shown != null) {
                JsonObject first = item.rows().getFirst();
                out.add(new Line(string(first, "uuid"), millis(first), first.has("conv") ? string(first, "conv") : null,
                        shown, List.copyOf(item.rows())));
            }
        }
        return out;
    }

    private static List<Item> parse(List<JsonObject> rows) {
        List<Item> out = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            JsonObject row = rows.get(i);
            switch (string(row, "type")) {
                case USER -> {
                    out.add(userItem(row));
                    i++;
                }
                case ASSISTANT -> {
                    if (bool(row, "isApiErrorMessage")) {
                        out.add(new Item(Role.TALK, errorOf(row), List.of(row)));
                        i++;
                        break;
                    }
                    List<JsonObject> group = new ArrayList<>();
                    String id = messageId(row);
                    while (i < rows.size() && ASSISTANT.equals(string(rows.get(i), "type"))
                            && !bool(rows.get(i), "isApiErrorMessage") && id.equals(messageId(rows.get(i)))) {
                        group.add(rows.get(i));
                        i++;
                    }
                    out.add(new Item(Role.TALK, new ConvoState.Msg.Assistant(turnOf(group)), group));
                }
                case ATTACHMENT -> {
                    JsonObject attachment = row.get("attachment") instanceof JsonObject a ? a : new JsonObject();
                    boolean persona = PERSONA_CHANGED.equals(string(attachment, "type"));
                    out.add(persona
                            ? new Item(Role.DIVIDER, new ConvoState.Msg.User(PERSONA_DIVIDER), List.of(row))
                            : new Item(Role.NEITHER, null, List.of(row)));
                    i++;
                }
                case SYSTEM -> {
                    out.add(COMPACT_BOUNDARY.equals(string(row, "subtype"))
                            ? new Item(Role.DIVIDER, new ConvoState.Msg.User(COMPACT_DIVIDER), List.of(row))
                            : new Item(Role.NEITHER, null, List.of(row)));
                    i++;
                }
                default -> {
                    out.add(new Item(Role.NEITHER, null, List.of(row)));   // 认不得的类型与插件的行:跳过,留在文件里
                    i++;
                }
            }
        }
        return out;
    }

    private static Item userItem(JsonObject row) {
        JsonObject message = row.get("message") instanceof JsonObject m ? m : new JsonObject();
        JsonElement content = message.get("content");
        if (content != null && content.isJsonArray()) {
            for (JsonElement block : content.getAsJsonArray()) {
                if (block.isJsonObject() && "tool_result".equals(string(block.getAsJsonObject(), "type"))) {
                    JsonObject result = block.getAsJsonObject();
                    return new Item(Role.TALK, new ConvoState.Msg.Tool(string(result, "tool_use_id"),
                            string(result, "content")), List.of(row));
                }
            }
            return new Item(Role.NEITHER, null, List.of(row));
        }
        String text = content == null || content.isJsonNull() ? "" : content.getAsString();
        if (bool(row, "isCompactSummary")) {
            return new Item(Role.CONTEXT_ONLY, new ConvoState.Msg.User(text), List.of(row));
        }
        if (INTERRUPTED.equals(text)) {
            return new Item(Role.TALK, new ConvoState.Msg.Halt(OWNER_STOP_WORDS, OWNER_STOP_CODE, List.of()), List.of(row));
        }
        return new Item(bool(row, "isMeta") ? Role.CONTEXT_ONLY : Role.TALK,
                new ConvoState.Msg.User(text, bool(row, "isMeta"), row.has("from") ? string(row, "from") : null), List.of(row));
    }

    private static ConvoState.Msg.Halt errorOf(JsonObject row) {
        String code = row.has("code") ? string(row, "code") : null;
        List<String> args = new ArrayList<>();
        if (row.get("args") instanceof JsonArray a) {
            a.forEach(e -> args.add(e.getAsString()));
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement block : contentBlocks(row)) {
            if (block.isJsonObject() && "text".equals(string(block.getAsJsonObject(), "type"))) {
                text.append(string(block.getAsJsonObject(), "text"));
            }
        }
        return new ConvoState.Msg.Halt(text.toString(), code, args);
    }

    private static AssistantTurn turnOf(List<JsonObject> group) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<LlmToolCall> calls = new ArrayList<>();
        JsonObject extras = new JsonObject();
        String origin = null;
        JsonObject first = group.getFirst();
        if (first.get("extras") instanceof JsonObject e) {
            extras = e.deepCopy();
        }
        if (first.has("origin")) {
            origin = string(first, "origin");
        }
        for (JsonObject row : group) {
            for (JsonElement el : contentBlocks(row)) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject block = el.getAsJsonObject();
                switch (string(block, "type")) {
                    case "text" -> content.append(string(block, "text"));
                    case "thinking" -> {
                        reasoning.append(string(block, "thinking"));
                        if (block.has("signature")) {
                            extras.add(AnthropicProvider.SIGNATURE_KEY, block.get("signature"));
                        }
                    }
                    case "tool_use" -> calls.add(new LlmToolCall(string(block, "id"), string(block, "name"),
                            block.get("input") == null ? "{}" : block.get("input").toString()));
                    default -> { }
                }
            }
        }
        return new AssistantTurn(content.toString(), calls, extras, reasoning.toString(), origin);
    }

    private static String messageId(JsonObject row) {
        return row.get("message") instanceof JsonObject m ? string(m, "id") : "";
    }

    private static JsonArray contentBlocks(JsonObject row) {
        return row.get("message") instanceof JsonObject m && m.get("content") instanceof JsonArray a ? a : new JsonArray();
    }

    private static long millis(JsonObject row) {
        try {
            return Instant.parse(string(row, "timestamp")).toEpochMilli();
        } catch (java.time.format.DateTimeParseException unreadable) {
            return 0L;
        }
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    private static String string(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? "" : el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }
}
