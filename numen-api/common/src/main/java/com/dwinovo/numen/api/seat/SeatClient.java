package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.sdk.Dispatcher;
import com.dwinovo.numen.mcp.rpc.RpcError;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import com.dwinovo.numen.mcp.rpc.RpcPeer;
import com.dwinovo.numen.script.ApiReply;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 座位的客户端一端:主人电脑上的大脑坐在这里。按座位上的方法({@link SeatMethods})向服务端要东西、发话,收服务端的通知(事件、目录变了),
 * 替服务端上跑着的程序执行它调的客户端函数({@code client/call})。一个 {@code SeatClient} 对应一条到服务器的连接,
 * 上面可以坐这位玩家创建的几只同伴。
 *
 * <p>不碰客户端类:产品里把它接在 MC 的连接上({@code ClientSeats}),单测与没有真客户端的进程里经进程内的管道({@link SeatPipe})
 * 接在服务端的座位上,两边是同一份代码。
 *
 * <p>线程:{@link #receive} 任何线程;通知的监听在收到它的线程上跑,不许阻塞;客户端函数在 {@code main} 上执行。
 */
public final class SeatClient {

    private final Executor main;
    private final RpcPeer peer;
    private final RpcPeer.Outbound out;
    /** 每只同伴收到的最大序号:断线重连时 {@link SeatMethods#SEAT_OPEN} 报上它。 */
    private final Map<UUID, Long> marks = new ConcurrentHashMap<>();
    private final List<Consumer<SeatEvent>> onEvent = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<UUID, String>> onCatalogChanged = new CopyOnWriteArrayList<>();
    /** 她的模块在这一头怎么按内容哈希取:哈希到正文。 */
    private final Map<UUID, Function<String, String>> modules = new ConcurrentHashMap<>();

    /**
     * @param main     客户端函数在哪里执行(客户端主线程)
     * @param handlers 服务端请求的处理在哪里跑,可以阻塞(等客户端主线程执行完)
     * @param out      把一条消息送到服务端
     */
    public SeatClient(Executor main, Executor handlers, RpcPeer.Outbound out) {
        this.main = main;
        this.out = out;
        this.peer = new RpcPeer(handlers);
        peer.onRequest(SeatMethods.CLIENT_CALL, this::clientCall);
        peer.onNotification(SeatMethods.EVENTS_NEW, this::eventArrived);
        peer.onNotification(SeatMethods.CATALOG_CHANGED, this::catalogChanged);
        peer.onRequest(SeatMethods.MODULES_GET, this::modulesGet);
    }

    /** 服务端发来一条消息。 */
    public void receive(RpcMessage message) {
        peer.receive(message, out);
    }

    /** 连接断了:在等答复的请求以失败收场。 */
    public void close() {
        peer.failPending(new java.io.IOException("the connection to the server closed"));
    }

    // ---- 监听 ----

    /** 听座位上的事件。 */
    public void onEvent(Consumer<SeatEvent> listener) {
        onEvent.add(listener);
    }

    /**
     * 服务端来要她的模块时,按内容哈希取正文的人;没有这一份时给 {@code null}。每只同伴一个。
     */
    public void serveModules(UUID companion, Function<String, String> byHash) {
        modules.put(companion, byHash);
    }

    /** 听目录变了:同伴、新的目录版本。 */
    public void onCatalogChanged(BiConsumer<UUID, String> listener) {
        onCatalogChanged.add(listener);
    }

    /** 这只同伴的座位上收到的最大序号;一条都没收到是 0。 */
    public long lastSeq(UUID companion) {
        return marks.getOrDefault(companion, 0L);
    }

    // ---- 请求 ----

    /**
     * 坐上她的座位。服务端把序号在 {@code lastSeq} 之后的事件补过来(作为通知,先于答复到达)。
     *
     * @param lastSeq 上一次连接里收到的最后一个序号;没有是 0
     */
    public CompletableFuture<Opened> open(UUID companion, long lastSeq) {
        marks.merge(companion, lastSeq, Math::max);
        JsonObject extra = new JsonObject();
        extra.addProperty("last_seq", lastSeq);
        return call(SeatMethods.SEAT_OPEN, companion, extra).thenApply(o -> {
            Opened opened = Opened.fromJson(o);
            if (opened.reset()) {
                marks.put(companion, opened.lastSeq());
            }
            return opened;
        });
    }

    /** 她能做什么(目录)。 */
    public CompletableFuture<JsonObject> catalog(UUID companion) {
        return call(SeatMethods.CATALOG_GET, companion, new JsonObject());
    }

    /**
     * 跑一段程序,等结局。
     *
     * @param generation 脑子以为的她的身体的代数;对不上服务端拒
     * @param program    这段程序的编号,在这只同伴的座位上不重
     * @param modules    她的模块清单:名字到内容哈希;服务端缺哪份经 {@code modules/get} 来要({@link #serveModules})
     * @param disabled   她对自己关掉的内置模块
     */
    public CompletableFuture<ProgramResult> run(UUID companion, long generation, String program, String code,
                                                Map<String, String> modules, Set<String> disabled) {
        JsonObject extra = new JsonObject();
        extra.addProperty("generation", generation);
        extra.addProperty("program", program);
        extra.addProperty("code", code);
        JsonObject listed = new JsonObject();
        modules.forEach(listed::addProperty);
        extra.add("modules", listed);
        JsonArray off = new JsonArray();
        disabled.forEach(off::add);
        extra.add("disabled", off);
        return call(SeatMethods.PROGRAM_RUN, companion, extra).thenApply(ProgramResult::fromJson);
    }

    /** 一个内置模块的原文;服务端没有这一份是 {@code null}。 */
    public CompletableFuture<String> builtinModule(UUID companion, String name) {
        JsonObject extra = new JsonObject();
        extra.addProperty("name", name);
        return call(SeatMethods.MODULES_BUILTIN, companion, extra)
                .thenApply(o -> o.has("code") ? o.get("code").getAsString() : null);
    }

    /** 一份模块写得读不读得通、撞没撞第 ① 层;没事是 {@code null},有事是那句话。 */
    public CompletableFuture<String> checkModule(UUID companion, String name, String code) {
        JsonObject extra = new JsonObject();
        extra.addProperty("name", name);
        extra.addProperty("code", code);
        return call(SeatMethods.MODULES_CHECK, companion, extra)
                .thenApply(o -> o.has("problem") ? o.get("problem").getAsString() : null);
    }

    /** 一份随发布的技能:说明、正文与附属文件清单。 */
    public CompletableFuture<SkillText> skill(UUID companion, String name) {
        JsonObject extra = new JsonObject();
        extra.addProperty("name", name);
        return call(SeatMethods.SKILL_READ, companion, extra).thenApply(o -> {
            java.util.List<String> files = new java.util.ArrayList<>();
            o.getAsJsonArray("files").forEach(f -> files.add(f.getAsString()));
            return new SkillText(o.get("description").getAsString(), o.get("content").getAsString(), files);
        });
    }

    /** 一份随发布的技能的一个附属文件。 */
    public CompletableFuture<String> skillFile(UUID companion, String name, String file) {
        JsonObject extra = new JsonObject();
        extra.addProperty("name", name);
        extra.addProperty("file", file);
        return call(SeatMethods.SKILL_READ, companion, extra).thenApply(o -> o.get("text").getAsString());
    }

    /** 叫程序停在调用之间。 */
    public CompletableFuture<Void> interrupt(UUID companion, String program, String why) {
        JsonObject extra = new JsonObject();
        extra.addProperty("program", program);
        extra.addProperty("why", why);
        return call(SeatMethods.PROGRAM_INTERRUPT, companion, extra).thenApply(o -> null);
    }

    /** 当场切断程序;{@code stopBody} 同时叫停它派的身体活。 */
    public CompletableFuture<Void> cut(UUID companion, String program, boolean stopBody) {
        JsonObject extra = new JsonObject();
        extra.addProperty("program", program);
        extra.addProperty("stop_body", stopBody);
        return call(SeatMethods.PROGRAM_CUT, companion, extra).thenApply(o -> null);
    }

    /** 叫停身体上在跑的活。 */
    public CompletableFuture<Void> stopBody(UUID companion) {
        return call(SeatMethods.BODY_STOP, companion, new JsonObject()).thenApply(o -> null);
    }

    /** 她此刻的样子。 */
    public CompletableFuture<Sitting> state(UUID companion) {
        return call(SeatMethods.STATE_GET, companion, new JsonObject()).thenApply(o -> new Sitting(
                o.get("generation").getAsLong(), SeatState.fromJson(o)));
    }

    /** 她要说的话,服务端在聊天里说出来。 */
    public CompletableFuture<Void> say(UUID companion, String text) {
        JsonObject extra = new JsonObject();
        extra.addProperty("text", text);
        return call(SeatMethods.SAY, companion, extra).thenApply(o -> null);
    }

    /** 收到 {@code seq} 号及以前的事件了:服务端不再留。 */
    public CompletableFuture<Void> ack(UUID companion, long seq) {
        JsonObject extra = new JsonObject();
        extra.addProperty("seq", seq);
        return call(SeatMethods.EVENTS_ACK, companion, extra).thenApply(o -> null);
    }

    /** {@link #skill} 的答复。 */
    public record SkillText(String description, String content, java.util.List<String> files) {}

    /** {@link #state} 的答复:她的身体此刻的代数,与她此刻的样子。 */
    public record Sitting(long generation, SeatState state) {}

    private CompletableFuture<JsonObject> call(String method, UUID companion, JsonObject params) {
        params.addProperty("companion", companion.toString());
        return peer.request(method, params, out).result().thenApply(JsonElement::getAsJsonObject);
    }

    // ---- 服务端发来的 ----

    private void eventArrived(JsonObject params) {
        SeatEvent event;
        try {
            event = SeatEvent.fromJson(params);
        } catch (RuntimeException unreadable) {
            Constants.LOG.warn("[numen-seat] dropped an unreadable event: {}", unreadable.toString());
            return;
        }
        marks.merge(event.companion(), event.seq(), Math::max);
        onEvent.forEach(listener -> listener.accept(event));
    }

    /** 服务端要她的几份模块:{@code hashes} 里每个哈希的正文;这里没有的哈希不在答复里。 */
    private JsonElement modulesGet(RpcPeer.Incoming in) throws Exception {
        UUID companion = Params.uuid(in.params(), "companion");
        Function<String, String> byHash = modules.get(companion);
        if (byHash == null) {
            throw new RpcException(RpcError.INVALID_PARAMS, "no modules are served for " + companion);
        }
        JsonObject found = new JsonObject();
        for (JsonElement hash : in.params().getAsJsonArray("hashes")) {
            String code = byHash.apply(hash.getAsString());
            if (code != null) {
                found.addProperty(hash.getAsString(), code);
            }
        }
        JsonObject answer = new JsonObject();
        answer.add("modules", found);
        return answer;
    }

    private void catalogChanged(JsonObject params) {
        try {
            UUID companion = Params.uuid(params, "companion");
            String version = Params.string(params, "catalog");
            onCatalogChanged.forEach(listener -> listener.accept(companion, version));
        } catch (RpcException unreadable) {
            Constants.LOG.warn("[numen-seat] dropped an unreadable catalog notice: {}", unreadable.getMessage());
        }
    }

    /**
     * 程序里的客户端函数:在客户端主线程上执行,答复就是那次调用的 {@code ApiReply}。答复装不下一条消息的上限时由 {@code RpcPeer}
     * 换成一条说明这件事的错误答复({@link com.dwinovo.numen.mcp.rpc.MessageTooBig})。服务端撤回(超时、程序结束)时处理线程被打断,不再答复。
     */
    private JsonElement clientCall(RpcPeer.Incoming in) throws Exception {
        UUID companion = Params.uuid(in.params(), "companion");
        String function = Params.string(in.params(), "function");
        JsonObject args = Params.object(in.params(), "args");
        CompletableFuture<String> reply = new CompletableFuture<>();
        main.execute(() -> Dispatcher.client(function, args, companion, reply::complete));
        return JsonParser.parseString(reply.get());
    }
}
