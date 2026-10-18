package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.ai.AiLog;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.store.AppendLog;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.StoreException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一只同伴的对话日志:{@code companions/<uuid>/chat.jsonl}。落盘的可靠性(头与版本、id、追加、坏行、整份迁移)
 * 都是 {@link AppendLog} 的事;本类只保留<b>记录格式</b>——有哪些记录类型、怎么推出给模型的上下文与给面板的视图。
 *
 * <h2>记录(第 4 版)</h2>
 * 每条记录有 {@code type}、{@code id}、{@code parentId}、{@code ts}(见 {@link AppendLog}),其余字段是这一类自己的:
 * <ul>
 *   <li>{@code message}:一条消息,{@code role} 是 user/assistant/tool,assistant 带着出处({@code origin}:协议/厂家/型号)、
 *       思考与私货;会话印 {@code conv} 记这一行是在哪个会话里写下的(null = 就他俩),模型不读它,只有面板拿它切视图。</li>
 *   <li>{@code halt}:一轮在这里被切断,带原因({@code reason},给模型的话)与结构化的因({@code code}+{@code args},面板翻成主人的语言)。</li>
 *   <li>{@code compact}:整理。只写摘要({@code content})和"从哪条 id 起原文保留"({@code firstKeptId});
 *       保留的那几条仍是它们原来的记录,不抄第二份。</li>
 *   <li>{@code clear}:这里清空过上下文。{@code persona-change}:这里换过人设(只记何时,不记换成了什么——绑定的真源是
 *       {@code binding.json})。</li>
 *   <li>{@code model-change}、{@code thinking-change}:这里起换了模型 / 换了思考档位,各自单独一条。</li>
 *   <li>{@code <命名空间>:<名字>}:插件自己的记录({@link #appendCustom}),不进模型上下文,也不进面板。</li>
 * </ul>
 * 认不得的类型两个视图都跳过,仍留在文件里(新版本加的类型旧版本不会弄丢)。
 *
 * <h2>两个视图</h2>
 * {@link #load} 是给模型的上下文:整理从它的摘要起重放、再接上 {@code firstKeptId} 起保留的那几条;清空从零起。
 * {@link #loadDisplay} 是面板看到的物理记录:整理、清空、换人设画成分隔记号,历史一条不丢。
 * 切断点两边都在:模型一侧交给 {@link ProtocolView},面板画成中断分隔。
 *
 * <h2>从旧版本升上来</h2>
 * 第 1~3 版的文件在第一次打开时整份升到当前版本(临时文件换进去,留 {@code .v<N>.bak},细节见 {@link AppendLog});
 * 升级步骤的每一步都只依赖前一版的样子。第 3 版的整理记录把保留的几条抄了一份,升级时换成指向原记录的指针。
 *
 * <p>写盘失败只记一条警告,对话在内存里照常进行——持久化不能让同伴下线。
 */
public final class ConvoLog {

    /** 记录格式的版本,写在头里;记录模型变了就加一。 */
    public static final int FORMAT_VERSION = 4;

    /** Soft cap on messages replayed into context (the file itself is unbounded). */
    public static final int DEFAULT_LOAD_LIMIT = 200;

    /** Compaction-boundary sentinel in the DISPLAY view — the GUI draws a thin divider. Never sent to the LLM. */
    public static final String COMPACT_DIVIDER = "[numen:compact-divider]";
    /** Persona-change sentinel in the DISPLAY view — drawn as a "人设已切换" divider. Never sent to the LLM. */
    public static final String PERSONA_DIVIDER = "[numen:persona-divider]";
    /** Context-clear sentinel in the DISPLAY view — drawn as a "上下文已清除" divider. Never sent to the LLM. */
    public static final String CLEAR_DIVIDER = "[numen:clear-divider]";

    private static final String MESSAGE = "message";
    private static final String HALT = "halt";
    private static final String COMPACT = "compact";
    private static final String CLEAR = "clear";
    private static final String PERSONA = "persona-change";
    private static final String MODEL = "model-change";
    private static final String THINKING = "thinking-change";

    /** 插件自己的记录类型:{@code 命名空间:名字},命名空间一般是模组 id。 */
    private static final Pattern CUSTOM_TYPE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** 第 3 版及更早把运行期状态块写在 user 消息头部(第 3 版升级时剥掉)。 */
    private static final String LEGACY_TASK_OPEN = "<current_task>";
    private static final String LEGACY_TASK_CLOSE = "</current_task>";

    private final Path file;
    private final AppendLog log;
    /** 面板对话记录的去处(见 {@link #onDisplay});没接就不显示。 */
    private java.util.function.Consumer<Line> displaySink = line -> { };
    /** 现在模型上下文由哪些记录组成(按序的 id)、最近一次记下的模型与思考档位;第一次要写之前才从文件建。 */
    private State state;

    private ConvoLog(Path file, Io io) {
        this.file = file;
        this.log = new AppendLog(file, format(file), io);
    }

    /** 会话日志的落点由宿主给全路径(按同伴归拢在 companions/&lt;uuid&gt;/)。第一次写之前不建文件。写在调用线程上直接做。 */
    public static ConvoLog atFile(Path file) {
        return atFile(file, Io.direct(e -> AiLog.LOG.warn("[numen-convo] {}", e.getMessage())));
    }

    /** 同上,写的线程由宿主给({@code io} 的执行器):服务端给每只同伴一条串行车道。 */
    public static ConvoLog atFile(Path file, Io io) {
        return new ConvoLog(file, io);
    }

    public Path file() {
        return file;
    }

    /**
     * 这份日志写下的每一条,换成面板对话记录里的样子交给 {@code sink}——换法与读盘的 {@link #loadLines}
     * 是同一个({@link #lineOf}),所以这一局边写边看到的,和下次进游戏读回来的一条不差。
     * 写盘失败也照样交:这一局得看得见刚发生的事。
     */
    public void onDisplay(java.util.function.Consumer<Line> sink) {
        this.displaySink = sink;
    }

    /**
     * 打开日志:旧版本的文件在这里整份升级(见类注释)。读和写本来就会先走这一步,显式调是为了让升级的错在启动时就暴露。
     * 升级不成功时文件原样,这里记一条警告,下次启动重来;这一局的读是空的、写不落盘。
     */
    public void migrateIfNeeded() {
        try {
            log.open();
        } catch (StoreException e) {
            AiLog.LOG.warn("[numen-convo] {}", e.getMessage());
        }
    }

    // ---- write ----

    /**
     * 追加一条消息;{@link ConvoState.Msg.Halt} 写成 {@code halt} 记录。写盘失败只告警。
     *
     * <p>{@code conversation} 盖成 {@code conv} 字段,与时间戳同族的记录级元数据：
     * 这一行是在哪个会话里记下的。<b>null = 就他俩</b>（她的单成员会话），所以
     * 这个功能之前的旧记录天然就落在单聊视图里，不需要迁移。模型不读它。
     */
    public void append(ConvoState.Msg msg, String conversation) {
        JsonObject body = encode(msg);
        if (conversation != null && !conversation.isBlank()) {
            body.addProperty("conv", conversation);
        }
        write(msg instanceof ConvoState.Msg.Halt ? HALT : MESSAGE, body);
    }

    /**
     * 记一条整理:{@code wrappedSummary} 是(已包好的)摘要,它替换掉边界之前的一切;最近 {@code keptTail} 条
     * <b>已经在日志里</b>的上下文消息原文保留,记录里只写从哪条 id 起——同一条消息不在文件里出现两次。
     * 文件 append-only,整理前的全部历史仍在盘上,{@link #load} 从这条边界起步。
     *
     * @param meta 只用于记账(触发方式、前后 token、耗时),重放不读它
     */
    public void appendCompactSummary(String wrappedSummary, int keptTail, JsonObject meta) {
        State s = state();
        if (keptTail < 0 || keptTail > s.context.size()) {
            throw new IllegalArgumentException("要保留最近 " + keptTail + " 条,上下文里只有 " + s.context.size() + " 条");
        }
        JsonObject body = new JsonObject();
        body.addProperty("content", wrappedSummary);
        List<String> kept = new ArrayList<>(s.context.subList(s.context.size() - keptTail, s.context.size()));
        if (!kept.isEmpty()) {
            body.addProperty("firstKeptId", kept.get(0));
        }
        if (meta != null && !meta.entrySet().isEmpty()) {
            body.add("meta", meta);
        }
        write(COMPACT, body);
    }

    /**
     * 把这本日志整本删掉(主人清空她的记忆):文件删了,上下文索引与最近的模型 / 档位记录也归零,
     * 之后写的第一条从一个新文件的头写起。删的活排在这本日志的写车道上,在它前面还没写下去的先落完。
     */
    public void wipe() {
        log.rewrite(List.of());
        state = new State();
    }

    /**
     * 记一条"这里清空过上下文"的边界。{@link #load} 从它之后从零重放——对模型是真空白;
     * {@link #loadDisplay} 渲染成 {@link #CLEAR_DIVIDER}。文件 append-only,清空不删任何历史,
     * 之前的对话全部留档、界面照常能翻。
     */
    public void appendClearBoundary() {
        write(CLEAR, new JsonObject());
    }

    /**
     * 记一条"这里换过人设"的分隔——只记<b>何时发生</b>,不记换成了什么:
     * 绑定的真源是同伴自己的 {@code binding.json},正文的真源是人设库。
     * 日志存副本就成了第二真源,改人设时必然对不上。
     * {@link #loadDisplay} 把它渲染成 {@link #PERSONA_DIVIDER};LLM 上下文里没有它。
     */
    public void appendPersonaDivider() {
        write(PERSONA, new JsonObject());
    }

    /**
     * 这一轮要用的端点和之前记下的不一样,就记下换了模型 / 换了思考档位——各自单独一条,没变不写。
     * 回合自己带着出处({@link LlmEndpoint#origin}),这两条是"从这里起换了"的分界,面板和模型以后据此知道何时换的。
     */
    public void noteEndpoint(LlmEndpoint endpoint) {
        State s = state();
        String origin = endpoint.origin();
        if (!origin.equals(s.origin)) {
            JsonObject body = new JsonObject();
            body.addProperty("origin", origin);
            body.addProperty("provider", endpoint.providerId());
            body.addProperty("model", endpoint.model().id());
            write(MODEL, body);
        }
        String thinking = endpoint.options().thinking().id();
        if (!thinking.equals(s.thinking)) {
            JsonObject body = new JsonObject();
            body.addProperty("level", thinking);
            write(THINKING, body);
        }
    }

    /**
     * 插件自己的一条记录。{@code type} 必须是 {@code 命名空间:名字}(命名空间一般是模组 id,小写字母数字与 {@code _.-});
     * {@code data} 随便什么 JSON 对象。它不进模型上下文也不进面板,重新加载时插件用 {@link #custom} 取回来恢复自己的状态。
     */
    public void appendCustom(String type, JsonObject data) {
        if (!CUSTOM_TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("插件记录的类型要写成 命名空间:名字,拿到 " + type);
        }
        JsonObject body = new JsonObject();
        body.add("data", data);
        write(type, body);
    }

    /** 一条插件记录:什么时候记的,和插件写进去的内容。 */
    public record Custom(long ts, JsonObject data) {}

    /** 某个类型的插件记录,按写下的顺序。读不了是空表(已经告警)。 */
    public List<Custom> custom(String type) {
        List<Custom> out = new ArrayList<>();
        for (AppendLog.Record r : records()) {
            if (type.equals(r.type()) && r.json().get("data") instanceof JsonObject data) {
                out.add(new Custom(r.ts(), data));
            }
        }
        return out;
    }

    /** 写一条记录、更新上下文索引,再把它交给面板。 */
    private void write(String type, JsonObject body) {
        State s = state();
        AppendLog.Record record = log.append(type, body);
        switch (type) {
            case MESSAGE, HALT -> s.context.add(record.id());
            case CLEAR -> s.context.clear();
            case COMPACT -> {
                String kept = record.string("firstKeptId");
                List<String> next = new ArrayList<>();
                next.add(record.id());
                if (kept != null) {
                    next.addAll(s.context.subList(s.context.indexOf(kept), s.context.size()));
                }
                s.context.clear();
                s.context.addAll(next);
            }
            case MODEL -> s.origin = record.string("origin");
            case THINKING -> s.thinking = record.string("level");
            default -> { }
        }
        Line shown = lineOf(record);
        if (shown != null) {
            displaySink.accept(shown);
        }
    }

    /** 模型上下文的组成与最近的模型 / 档位;第一次要写之前从文件建,之后随写更新。 */
    private static final class State {
        final List<String> context = new ArrayList<>();
        String origin;
        String thinking;
    }

    private State state() {
        if (state == null) {
            State s = new State();
            List<AppendLog.Record> all = records();
            for (AppendLog.Record r : all) {
                switch (r.type()) {
                    case MODEL -> s.origin = r.string("origin");
                    case THINKING -> s.thinking = r.string("level");
                    default -> { }
                }
            }
            for (Entry e : contextOf(all)) {
                s.context.add(e.id());
            }
            state = s;
        }
        return state;
    }

    // ---- read ----

    /** 全部记录;读不了(含升级失败)记一条警告,当空。 */
    private List<AppendLog.Record> records() {
        try {
            AppendLog.Loaded loaded = log.read();
            if (loaded.skipped() > 0) {
                AiLog.LOG.warn("[numen-convo] skipped {} unreadable line(s) in {}", loaded.skipped(), file.getFileName());
            }
            return loaded.records();
        } catch (StoreException e) {
            AiLog.LOG.warn("[numen-convo] {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Load the tail of the conversation for the LLM: messages and cut-off points (other records skipped), the
     * last {@code limit} extended backwards to the nearest {@code user} message so the slice starts at a
     * turn boundary. A {@code compact} record restarts the replay from its summary + the tail it points at;
     * a {@code clear} record restarts it from nothing; a {@code halt} record replays as a
     * {@link ConvoState.Msg.Halt} where the turn was cut off.
     */
    public List<ConvoState.Msg> load(int limit) {
        List<ConvoState.Msg> all = new ArrayList<>();
        for (Entry e : contextOf(records())) {
            all.add(e.msg());
        }
        if (all.size() <= limit) return all;

        // Tail-trim, then walk back to the nearest user message (a slice opening on a tool result or
        // mid-chain assistant turn is rejected by the API; a conversation always begins with user).
        int start = all.size() - limit;
        while (start > 0 && !(all.get(start) instanceof ConvoState.Msg.User)) {
            start--;
        }
        List<ConvoState.Msg> tail = all.subList(start, all.size());
        AiLog.LOG.info("[numen-convo] loaded {}/{} msgs from {}",
                tail.size(), all.size(), file.getFileName());
        return new ArrayList<>(tail);
    }

    /** 上下文里的一项:哪条记录、解出来的消息。整理的摘要也是一项,id 是整理记录自己的。 */
    private record Entry(String id, ConvoState.Msg msg) {}

    /** 从记录推出模型上下文;{@link #load} 与写之前建索引共用这一个推法。 */
    private static List<Entry> contextOf(List<AppendLog.Record> records) {
        List<Entry> context = new ArrayList<>();
        for (AppendLog.Record r : records) {
            switch (r.type()) {
                case MESSAGE -> {
                    ConvoState.Msg m = decodeMessage(r.json());   // 认不得的 role:跳过,留在文件里
                    if (m != null) context.add(new Entry(r.id(), m));
                }
                case HALT -> context.add(new Entry(r.id(), decodeHalt(r.json())));
                case CLEAR -> context.clear();   // 白纸重来:无摘要、无保留
                case COMPACT -> {
                    List<Entry> next = new ArrayList<>();
                    next.add(new Entry(r.id(), new ConvoState.Msg.User(str(r.json().get("content")))));
                    String kept = r.string("firstKeptId");
                    if (kept != null) {
                        int from = indexOf(context, kept);
                        if (from < 0) {
                            AiLog.LOG.warn("[numen-convo] compact record {} points at a missing record {}", r.id(), kept);
                        } else {
                            next.addAll(context.subList(from, context.size()));
                        }
                    }
                    context = next;
                }
                default -> { }   // persona-change、model-change、插件记录与不认得的类型:不进模型上下文
            }
        }
        return context;
    }

    private static int indexOf(List<Entry> context, String id) {
        for (int i = 0; i < context.size(); i++) {
            if (context.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    /**
     * Load the PHYSICAL tail for the chat GUI: messages in file order; {@code compact}, {@code clear}
     * and {@code persona-change} records become sentinel divider messages instead of restarting or
     * vanishing, and a {@code halt} record stays the {@link ConvoState.Msg.Halt} it was appended as —
     * the GUI draws it as an interruption divider carrying its reason. This is the "what actually
     * happened" view — history is never lost to compaction or clearing.
     */
    public List<ConvoState.Msg> loadDisplay(int limit) {
        List<ConvoState.Msg> out = new ArrayList<>();
        for (Line line : loadLines(limit)) out.add(line.msg());
        return out;
    }

    /**
     * 面板对话记录的一行:消息本身,加上它是什么时候记的、记在哪个会话名下。把成员各自的日志
     * 归并成一个会话的视图,靠的就是这两样(按时间交错、按会话过滤),消息自己不带。
     * {@code conv} 为 null = 就他俩(她的单成员会话)。
     */
    public record Line(String id, long ts, String conv, ConvoState.Msg msg) {}

    /**
     * 往前翻的一页:最多 {@code limit} 行面板记录(文件顺序),以及再往前还有没有记录。翻页的游标就是最老那一行的 {@link Line#id}。
     */
    public record Page(List<Line> lines, boolean more) {}

    /**
     * 面板记录里 {@code beforeId} 那一行之前的一页;{@code beforeId} 为 null 是最新一页。按 id 往前读,不读整份:
     * 面板打开时取最新一页,往上翻再取更早的,历史再长也只付这一页的钱。
     */
    public Page linesBefore(String beforeId, int limit) {
        List<Line> got = new ArrayList<>();
        String cursor = beforeId;
        String oldestRecord = beforeId;
        try {
            while (got.size() < limit) {
                List<AppendLog.Record> chunk = log.readBefore(cursor, Math.max(limit, 64)).records();
                if (chunk.isEmpty()) {
                    break;
                }
                for (int i = chunk.size() - 1; i >= 0 && got.size() < limit; i--) {
                    oldestRecord = chunk.get(i).id();
                    Line shown = lineOf(chunk.get(i));
                    if (shown != null) {
                        got.add(0, shown);
                    }
                }
                cursor = oldestRecord;
            }
            boolean more = oldestRecord != null && !log.readBefore(oldestRecord, 1).records().isEmpty();
            return new Page(got, more);
        } catch (StoreException e) {
            AiLog.LOG.warn("[numen-convo] {}", e.getMessage());
            return new Page(List.of(), false);
        }
    }

    /** 同 {@link #loadDisplay},每行连着时间戳与会话印。 */
    public List<Line> loadLines(int limit) {
        List<Line> all = new ArrayList<>();
        for (AppendLog.Record r : records()) {
            Line shown = lineOf(r);
            if (shown != null) all.add(shown);
        }
        if (all.size() <= limit) return all;
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    /** 一条记录在面板里的样子,连同它的时间戳与会话印;不显示的记录返回 null。 */
    private static Line lineOf(AppendLog.Record record) {
        ConvoState.Msg shown = displayOf(record);
        if (shown == null) return null;
        return new Line(record.id(), record.ts(), record.string("conv"), shown);
    }

    private static final Pattern QUERY = Pattern.compile("(?s)<query>(.*?)</query>");

    /**
     * 一条 user 消息里主人的原话——{@code <query>} 标记里的那些,按出现顺序;没有标记则空。
     * {@code <query>} 怎么认<b>只有这一处</b>:面板剥记号、归并去重都从这里取,别各写一份正则。
     */
    public static List<String> queries(String content) {
        List<String> out = new ArrayList<>();
        if (content == null) return out;
        java.util.regex.Matcher m = QUERY.matcher(content);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * 一条日志记录在面板对话记录里的样子:消息原样;整理、清空、换人设画成分隔记号;切断点照原样。
     * 换模型、插件记录、不认得的类型不显示,返回 {@code null}。边写边看与读盘共用这一个换法。
     * {@code restored} 的消息只是升级时补回上下文的原文副本,原话早已在更前面显示过。
     */
    private static ConvoState.Msg displayOf(AppendLog.Record record) {
        return switch (record.type()) {
            case MESSAGE -> record.json().has(RESTORED) ? null : decodeMessage(record.json());
            case HALT -> decodeHalt(record.json());
            case COMPACT -> new ConvoState.Msg.User(COMPACT_DIVIDER);
            case CLEAR -> new ConvoState.Msg.User(CLEAR_DIVIDER);
            case PERSONA -> new ConvoState.Msg.User(PERSONA_DIVIDER);
            default -> null;
        };
    }

    /**
     * <b>迁移专用</b>:旧版把人设绑定记在 {@code persona-change} 事件里(事件溯源、后写胜出);升到第 4 版时
     * 那个值挪到 {@code personaId}(原来的 {@code id} 字段现在是记录自己的 id)。返回最后一条的值供
     * {@code binding.json} 接管;新写的分隔不带它,所以迁移完这里恒返回 null。旧布局清空后整个方法可以删掉。
     */
    public String legacyPersonaId() {
        String current = null;
        for (AppendLog.Record r : records()) {
            if (PERSONA.equals(r.type())) {
                String id = r.string("personaId");
                current = id == null || id.isBlank() ? null : id;
            }
        }
        return current;
    }

    // ---- codec ----

    /** 一条消息的记录体(不含 type/id/ts 这些记录级字段)。 */
    private static JsonObject encode(ConvoState.Msg msg) {
        JsonObject o = new JsonObject();
        switch (msg) {
            case ConvoState.Msg.User u -> {
                o.addProperty("role", "user");
                o.addProperty("content", u.content());
            }
            case ConvoState.Msg.Assistant a -> {
                o.addProperty("role", "assistant");
                o.addProperty("content", a.turn().content());
                if (a.turn().hasToolCalls()) {
                    JsonArray calls = new JsonArray();
                    for (LlmToolCall tc : a.turn().toolCalls()) {
                        JsonObject c = new JsonObject();
                        c.addProperty("id", tc.id());
                        c.addProperty("name", tc.name());
                        c.addProperty("arguments", tc.arguments());
                        calls.add(c);
                    }
                    o.add("tool_calls", calls);
                }
                // Provider extras (e.g. DeepSeek reasoning_content) must survive the round-trip.
                if (!a.turn().extras().entrySet().isEmpty()) {
                    o.add("extras", a.turn().extras());
                }
                // 思考文本同样得跟着落盘:面板读回来要画思考块;Anthropic 回传思考块时连签名一起要它原文。
                if (a.turn().hasReasoning()) {
                    o.addProperty("reasoning", a.turn().reasoning());
                }
                // 出处随回合落盘:重进游戏后还得认得出那些私货是哪家的
                if (a.turn().origin() != null) {
                    o.addProperty("origin", a.turn().origin());
                }
            }
            case ConvoState.Msg.Tool t -> {
                o.addProperty("role", "tool");
                o.addProperty("tool_call_id", t.toolCallId());
                o.addProperty("content", t.content());
            }
            case ConvoState.Msg.Halt h -> {
                o.addProperty("reason", h.reason());
                if (h.code() != null) {
                    o.addProperty("code", h.code());
                    JsonArray args = new JsonArray();
                    h.args().forEach(args::add);
                    o.add("args", args);
                }
            }
        }
        return o;
    }

    private static ConvoState.Msg.Halt decodeHalt(JsonObject o) {
        String code = o.has("code") ? str(o.get("code")) : null;
        List<String> args = new ArrayList<>();
        if (o.get("args") instanceof JsonArray a) {
            a.forEach(e -> args.add(str(e)));
        }
        return new ConvoState.Msg.Halt(str(o.get("reason")), code, args);
    }

    /** Decode a message record, or {@code null} for an unknown role (forward-compat: skip, don't crash). */
    private static ConvoState.Msg decodeMessage(JsonObject o) {
        String role = str(o.get("role"));
        return switch (role) {
            case "user" -> new ConvoState.Msg.User(str(o.get("content")));
            case "tool" -> new ConvoState.Msg.Tool(str(o.get("tool_call_id")), str(o.get("content")));
            case "assistant" -> {
                List<LlmToolCall> calls = new ArrayList<>();
                if (o.has("tool_calls") && o.get("tool_calls").isJsonArray()) {
                    for (JsonElement el : o.getAsJsonArray("tool_calls")) {
                        JsonObject c = el.getAsJsonObject();
                        calls.add(new LlmToolCall(
                                str(c.get("id")), str(c.get("name")), str(c.get("arguments"))));
                    }
                }
                JsonObject extras = o.has("extras") && o.get("extras").isJsonObject()
                        ? o.getAsJsonObject("extras") : null;
                yield new ConvoState.Msg.Assistant(
                        new AssistantTurn(str(o.get("content")), calls, extras, str(o.get("reasoning")),
                                o.has("origin") && !o.get("origin").isJsonNull()
                                        ? o.get("origin").getAsString() : null));
            }
            default -> null;   // unknown role → forward-compat skip
        };
    }

    private static String str(JsonElement el) {
        return el == null || el.isJsonNull() ? "" : el.getAsString();
    }

    // ---- 面板行上线的样子 ----

    /**
     * 一行面板记录送给面板的 JSON:编号、时刻、会话印,加上消息本身(和落盘同一个记录体,切断点另标 {@code halt})。
     * 面板行在线上就是这个样子,不另写一套编码。
     */
    public static JsonObject toJson(Line line) {
        JsonObject o = encode(line.msg());
        o.addProperty("id", line.id());
        o.addProperty("ts", line.ts());
        if (line.conv() != null) {
            o.addProperty("conv", line.conv());
        }
        if (line.msg() instanceof ConvoState.Msg.Halt) {
            o.addProperty("halt", true);
        }
        return o;
    }

    /** {@link #toJson(Line)} 的逆;读不懂(未知角色)返回 {@code null}。 */
    public static Line lineFromJson(JsonObject o) {
        ConvoState.Msg msg = o.has("halt") ? decodeHalt(o) : decodeMessage(o);
        if (msg == null) {
            return null;
        }
        return new Line(o.has("id") ? str(o.get("id")) : null, o.has("ts") ? o.get("ts").getAsLong() : 0L,
                o.has("conv") ? str(o.get("conv")) : null, msg);
    }

    // ---- 旧版本升上来 ----

    /** 升级时补回上下文的消息副本的记号:模型上下文里有,面板不再显示第二遍。 */
    private static final String RESTORED = "restored";

    private static AppendLog.Format format(Path file) {
        return AppendLog.Format.of(FORMAT_VERSION)
                .upgrade(1, records -> v1ToV2(records, anchorMillis(file)))
                .upgrade(2, ConvoLog::v2ToV3)
                .upgrade(3, ConvoLog::v3ToV4);
    }

    private static long anchorMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    /** 第 1 版 → 第 2 版:老式 {@code role:"compact"} 行改成 {@code type:"compact"} 事件;没有 {@code ts} 的补一个。 */
    private static List<JsonObject> v1ToV2(List<JsonObject> records, long anchor) {
        long ts = anchor;
        for (JsonObject rec : records) {
            if ("compact".equals(str(rec.get("role")))) {
                rec.remove("role");
                rec.addProperty("type", COMPACT);
            }
            if (!rec.has("ts")) rec.addProperty("ts", ts++);
        }
        return records;
    }

    /**
     * 第 2 版 → 第 3 版:剥掉老版本写进 user 消息头部的 {@code <current_task>} 块——顶层的、整理摘要(重放时
     * 是第一条 user)、以及整理里保留的 user 消息。运行期状态现在每次请求现挂,历史里留一份旧的会和现挂的打架。
     */
    private static List<JsonObject> v2ToV3(List<JsonObject> records) {
        for (JsonObject rec : records) {
            if ("user".equals(str(rec.get("role")))) {
                stripContent(rec);
            } else if (COMPACT.equals(str(rec.get("type")))) {
                stripContent(rec);
                if (rec.has("preserved") && rec.get("preserved").isJsonArray()) {
                    for (JsonElement el : rec.getAsJsonArray("preserved")) {
                        if (el.isJsonObject() && "user".equals(str(el.getAsJsonObject().get("role")))) {
                            stripContent(el.getAsJsonObject());
                        }
                    }
                }
            }
        }
        return records;
    }

    private static void stripContent(JsonObject o) {
        String content = str(o.get("content"));
        String stripped = withoutLegacyCurrentTask(content);
        if (!stripped.equals(content)) {
            o.addProperty("content", stripped);
        }
    }

    /**
     * The text without the generated {@code <current_task>} block older builds put in front of the owner's
     * words. A block that starts inside {@code <query>} is the owner's own text and is left alone.
     */
    private static String withoutLegacyCurrentTask(String content) {
        int open = content.indexOf(LEGACY_TASK_OPEN);
        int query = content.indexOf("<query>");
        if (open < 0 || (query >= 0 && open > query)) return content;
        int close = content.indexOf(LEGACY_TASK_CLOSE, open + LEGACY_TASK_OPEN.length());
        if (close < 0) return content;
        int end = close + LEGACY_TASK_CLOSE.length();
        if (end < content.length() && content.charAt(end) == '\r') end++;
        if (end < content.length() && content.charAt(end) == '\n') end++;
        return (content.substring(0, open) + content.substring(end)).strip();
    }

    /** 第 3 版里上下文的一项:新记录的 id,和它在第 3 版里的样子(去掉时间与会话印),整理的保留列表按后者对账。 */
    private record Held(String id, JsonObject shape) {}

    /**
     * 第 3 版 → 第 4 版:
     * <ul>
     *   <li>没有 {@code type} 的行是消息(第 3 版按 {@code role} 分),补上 {@code type:"message"};每条记录先分到 id;</li>
     *   <li>{@code persona-change} 里记人设绑定的 {@code id} 挪到 {@code personaId}——{@code id} 现在是记录自己的;</li>
     *   <li>整理记录抄在 {@code preserved} 里的几条,换成指向原记录的 {@code firstKeptId}:这几条原本就是整理那一刻
     *       上下文的最后几项,逐条对账(忽略时间与会话印),对得上就指向那里;对不上(旧版本改写过的)就把抄的这几条
     *       作为 {@code restored} 消息补在整理记录前面,指向它们——模型上下文一条不少,面板不多显示一遍。</li>
     * </ul>
     */
    private static List<JsonObject> v3ToV4(List<JsonObject> legacy) {
        Set<String> used = new HashSet<>();
        List<JsonObject> out = new ArrayList<>();
        List<Held> context = new ArrayList<>();
        for (JsonObject rec : legacy) {
            String type = rec.has("type") ? str(rec.get("type")) : MESSAGE;
            switch (type) {
                case MESSAGE, HALT -> {
                    JsonObject o = emit(out, used, rec, type);
                    context.add(new Held(str(o.get("id")), shapeOf(rec)));
                }
                case CLEAR -> {
                    emit(out, used, rec, type);
                    context.clear();
                }
                case COMPACT -> context = compactToPointer(out, used, rec, context);
                case PERSONA -> {
                    JsonObject o = emit(out, used, rec, type);
                    if (rec.has("id")) {
                        o.add("personaId", rec.get("id"));
                    }
                }
                default -> {
                    JsonObject o = emit(out, used, rec, type);
                    if (rec.has("id")) {
                        o.add("legacyId", rec.get("id"));   // 不认得的记录自带的 id 字段:不丢,只是让位给记录自己的 id
                    }
                }
            }
        }
        return out;
    }

    /** 一条旧记录换成新记录,分一个新 id;旧记录自带的 {@code id} 字段由调用方按这一类的意思安置。 */
    private static JsonObject emit(List<JsonObject> out, Set<String> used, JsonObject rec, String type) {
        JsonObject o = rec.deepCopy();
        o.remove("id");
        o.remove("preserved");
        o.addProperty("type", type);
        o.addProperty("id", AppendLog.freshId(used));
        used.add(o.get("id").getAsString());
        out.add(o);
        return o;
    }

    private static List<Held> compactToPointer(List<JsonObject> out, Set<String> used, JsonObject rec,
                                               List<Held> context) {
        List<JsonObject> preserved = new ArrayList<>();
        if (rec.get("preserved") instanceof JsonArray array) {
            for (JsonElement el : array) {
                if (el.isJsonObject()) preserved.add(el.getAsJsonObject());
            }
        }
        int tail = context.size() - preserved.size();
        boolean matches = tail >= 0;
        for (int i = 0; matches && i < preserved.size(); i++) {
            matches = context.get(tail + i).shape().equals(shapeOf(preserved.get(i)));
        }
        String firstKept = null;
        List<Held> kept = new ArrayList<>();
        if (matches) {
            kept.addAll(context.subList(tail, context.size()));
        } else {
            for (JsonObject copy : preserved) {
                String type = copy.has("type") ? str(copy.get("type")) : MESSAGE;
                JsonObject restored = emit(out, used, copy, type);
                restored.addProperty(RESTORED, true);
                if (rec.has("ts")) restored.add("ts", rec.get("ts"));
                kept.add(new Held(str(restored.get("id")), shapeOf(copy)));
            }
        }
        if (!kept.isEmpty()) {
            firstKept = kept.get(0).id();
        }
        JsonObject o = emit(out, used, rec, COMPACT);
        if (firstKept != null) {
            o.addProperty("firstKeptId", firstKept);
        }
        List<Held> next = new ArrayList<>();
        next.add(new Held(str(o.get("id")), new JsonObject()));
        next.addAll(kept);
        return next;
    }

    /** 一条第 3 版记录的内容(去掉时间与会话印),用来对账。 */
    private static JsonObject shapeOf(JsonObject rec) {
        JsonObject shape = rec.deepCopy();
        shape.remove("ts");
        shape.remove("conv");
        return shape;
    }
}
