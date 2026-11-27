package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.talk.Spoken;
import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.skill.BundledSkills;
import com.dwinovo.numen.api.agent.skill.SkillInfo;
import com.dwinovo.numen.api.sdk.ApiSearch;
import com.dwinovo.numen.mcp.rpc.RpcError;
import com.dwinovo.numen.api.program.ClientTransport;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import com.dwinovo.numen.mcp.rpc.RpcPeer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端这头的一条座位连接:一位玩家的客户端对应一个 {@link RpcPeer},座位上的方法({@link SeatMethods})都挂在它上面。
 * 一条连接上可以坐着这位玩家创建的几只同伴的座位,每条消息的 {@code companion} 说是哪一只;没有先 {@code seat/open} 的,
 * 或不是他创建的,一律拒。连接断了({@link #close})就是座位上没人,在等的反向请求以失败收场。
 *
 * <p>线程:{@link #receive} 任何线程(产品里是网络线程);请求的处理在 {@link Seats} 给的执行体上,可以阻塞。
 */
final class SeatConnection {

    final UUID player;
    private final Seats seats;
    private final RpcPeer peer;
    private final RpcPeer.Outbound out;
    /** 这条连接上坐着的座位,按同伴。 */
    private final Map<UUID, Seat> opened = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * @param wire 把一条消息送到客户端:产品里是网络,单测与没有真客户端的进程里是进程内的管道
     */
    SeatConnection(Seats seats, UUID player, RpcPeer.Outbound wire) {
        this.seats = seats;
        this.player = player;
        this.out = message -> {
            if (closed) {
                throw new IOException("its owner's client disconnected");
            }
            wire.send(message);
        };
        this.peer = new RpcPeer(seats.handlers());
        peer.onRequest(SeatMethods.SEAT_OPEN, this::open);
        peer.onRequest(SeatMethods.CATALOG_GET, in -> seat(in).catalog());
        peer.onRequest(SeatMethods.PROGRAM_RUN, this::run);
        peer.onRequest(SeatMethods.PROGRAM_INTERRUPT, in -> {
            seat(in).interrupt(Params.string(in.params(), "program"), Params.string(in.params(), "why"));
            return new JsonObject();
        });
        peer.onRequest(SeatMethods.PROGRAM_CUT, in -> {
            seat(in).cut(Params.string(in.params(), "program"), Params.bool(in.params(), "stop_body", false));
            return new JsonObject();
        });
        peer.onRequest(SeatMethods.BODY_STOP, in -> seat(in).stopBody().toJson());
        peer.onRequest(SeatMethods.STATE_GET, this::state);
        peer.onRequest(SeatMethods.SAY, this::say);
        peer.onRequest(SeatMethods.SKILL_READ, this::readSkill);
        peer.onRequest(SeatMethods.API_SEARCH, this::searchApi);
        peer.onRequest(SeatMethods.CONVERSATION_OPEN, in -> seat(in).openConversation().get().toJson());
        peer.onNotification(SeatMethods.CONVERSATION_APPEND, this::append);
        peer.onNotification(SeatMethods.CONVERSATION_NEW, this::startConversation);
        peer.onRequest(SeatMethods.CONVERSATION_READ, this::readConversation);
        peer.onRequest(SeatMethods.CONVERSATION_PAGE, this::page);
        peer.onRequest(SeatMethods.CONVERSATION_WATCH, this::watch);
        peer.onNotification(SeatMethods.RUN_STARTED, this::runStarted);
        peer.onNotification(SeatMethods.RUN_ENDED, this::runEnded);
        peer.onRequest(SeatMethods.EVENTS_ACK, in -> {
            seat(in).ack(Params.requiredInteger(in.params(), "seq"));
            return new JsonObject();
        });
    }

    /** 客户端发来一条消息。 */
    void receive(RpcMessage message) {
        peer.receive(message, out);
    }

    /** 连接断了:座位上没人,在等的反向请求失败。 */
    void close() {
        closed = true;
        peer.failPending(new IOException("its owner's client disconnected"));
        opened.values().forEach(seat -> seat.detach(this));
        opened.clear();
    }

    /** 这条连接不再坐这只同伴的座位(被别的连接顶替)。 */
    void release(UUID companion) {
        opened.remove(companion);
    }

    // ---- 发给客户端 ----

    void send(SeatEvent event) {
        notify(SeatMethods.EVENTS_NEW, event.toJson());
    }

    /** 订阅了的对话记录里新落盘的行。 */
    void rows(UUID companion, UUID conversation, List<JsonObject> rows) {
        JsonObject params = new JsonObject();
        params.addProperty("companion", companion.toString());
        params.addProperty("conversation", conversation.toString());
        params.add("rows", ConversationOpened.array(rows));
        notify(SeatMethods.CONVERSATION_ROWS, params);
    }

    void catalogChanged(UUID companion, String version) {
        JsonObject params = new JsonObject();
        params.addProperty("companion", companion.toString());
        params.addProperty("catalog", version);
        notify(SeatMethods.CATALOG_CHANGED, params);
    }

    private void notify(String method, JsonObject params) {
        try {
            peer.notify(method, params, out);
        } catch (IOException gone) {
            // 连接已经断了(或消息装不下):客户端重连时 seat/open 带着序号补课,通知本身不重发
            Constants.LOG.debug("[numen-seat] {} to {} not delivered: {}", method, player, gone.toString());
        }
    }

    /** 向这条连接的客户端发一次反向请求;答复在返回的 {@link RpcPeer.Outgoing} 上。 */
    RpcPeer.Outgoing callClient(ClientTransport.Request request) {
        JsonObject params = new JsonObject();
        params.addProperty("companion", request.companion().toString());
        params.addProperty("function", request.function());
        params.add("args", request.arguments());
        return peer.request(SeatMethods.CLIENT_CALL, params, out);
    }

    /** 不再等这次反向请求的答复:告诉客户端撤回,晚到的答复不理。 */
    void cancelCall(RpcPeer.Outgoing call, String why) {
        peer.cancel(call, why, out);
    }

    // ---- 座位上的方法 ----

    private Seat seat(RpcPeer.Incoming in) throws RpcException {
        UUID companion = Params.uuid(in.params(), "companion");
        Seat seat = opened.get(companion);
        if (seat == null) {
            throw SeatErrors.noSeat(companion);
        }
        return seat;
    }

    private JsonElement open(RpcPeer.Incoming in) throws Exception {
        UUID companion = Params.uuid(in.params(), "companion");
        long lastSeq = Params.integer(in.params(), "last_seq", 0);
        Seat seat = seats.seatFor(this, companion);
        opened.put(companion, seat);
        Opened answer = seat.attach(this, lastSeq);
        if (closed) {
            seat.detach(this);   // 就在这时连接断了:座位不留给一条已经没有了的连接
            throw new IOException("its owner's client disconnected");
        }
        return answer.toJson();
    }

    /** 客户端追加了几行:通知在收到它的线程上按顺序处理,行按这个顺序落盘。 */
    private void append(JsonObject params) {
        UUID companion = seatedCompanion(params, "wrote rows of");
        if (companion == null) {
            return;
        }
        try {
            opened.get(companion).append(this, Params.uuid(params, "conversation"),
                    ConversationOpened.objects(params.getAsJsonArray("rows")));
        } catch (RpcException | RuntimeException unreadable) {
            Constants.LOG.warn("[numen-seat] dropped an unreadable append from {}: {}", player, unreadable.toString());
        }
    }

    private void startConversation(JsonObject params) {
        UUID companion = seatedCompanion(params, "started a conversation of");
        if (companion == null) {
            return;
        }
        try {
            opened.get(companion).startConversation(this, Params.uuid(params, "conversation"));
        } catch (RpcException | RuntimeException unreadable) {
            Constants.LOG.warn("[numen-seat] dropped an unreadable conversation start from {}: {}", player, unreadable.toString());
        }
    }

    private JsonElement readConversation(RpcPeer.Incoming in) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("text", seat(in).readConversation().get());
        return o;
    }

    /** 她的主人表上的人订阅她的对话记录,不要求坐着座位。 */
    private JsonElement watch(RpcPeer.Incoming in) throws Exception {
        UUID companion = Params.uuid(in.params(), "companion");
        requireKeeper(companion);
        seats.watch(companion, this);
        return new JsonObject();
    }

    /**
     * 翻一页对话记录,不要求坐着座位:单聊({@code companion})她的主人表上的人能翻;群({@code tag})翻他用得了的成员的、盖着这个印的行。
     */
    private JsonElement page(RpcPeer.Incoming in) throws Exception {
        String tag = Params.optionalString(in.params(), "tag");
        UUID companion = tag == null ? Params.uuid(in.params(), "companion") : null;
        String cursor = Params.optionalString(in.params(), "cursor");
        int limit = (int) Math.max(1, Math.min(History.PAGE, Params.integer(in.params(), "limit", History.PAGE)));
        if (companion != null) {
            requireKeeper(companion);
        }
        Map<UUID, UUID> members = seats.onMain(() -> {
            List<UUID> who = tag == null ? List.of(companion) : seats.world().membersOf(player, tag);
            Map<UUID, UUID> out = new java.util.LinkedHashMap<>();
            who.forEach(member -> out.put(member, seats.world().conversation(member)));
            return out;
        });
        return History.page(seats.world().transcripts(), members, tag, cursor, limit).get().toJson();
    }

    private void requireKeeper(UUID companion) throws Exception {
        if (!seats.onMain(() -> seats.world().keeps(companion, player))) {
            throw new RpcException(RpcError.INVALID_PARAMS, "you are not one of the keepers of " + companion);
        }
    }

    private JsonElement run(RpcPeer.Incoming in) throws Exception {
        Seat seat = seat(in);
        String named = Params.optionalString(in.params(), "keeper");
        UUID keeper = named == null ? null : Params.uuid(in.params(), "keeper");
        if (keeper != null && !seats.onMain(() -> seats.world().keeps(seat.companion, keeper))) {
            throw new RpcException(RpcError.INVALID_PARAMS, "params.keeper is not one of her keepers");
        }
        ProgramResult result = seat.run(this, Params.string(in.params(), "program"),
                Params.requiredInteger(in.params(), "generation"), Params.string(in.params(), "code"), keeper);
        return result.toJson();
    }

    private JsonElement searchApi(RpcPeer.Incoming in) throws Exception {
        Seat seat = seat(in);
        String query = Params.string(in.params(), "query");
        int max = (int) Params.integer(in.params(), "max_results", 0);
        JsonObject o = new JsonObject();
        try {
            o.addProperty("text", ApiSearch.search(query, max, seat.companion));
        } catch (IllegalArgumentException refused) {
            throw new RpcException(RpcError.INVALID_PARAMS, refused.getMessage());
        }
        return o;
    }

    private JsonElement readSkill(RpcPeer.Incoming in) throws Exception {
        Seat seat = seat(in);
        String name = Params.string(in.params(), "name");
        String file = Params.optionalString(in.params(), "file");
        JsonObject o = new JsonObject();
        try {
            if (file == null) {
                SkillInfo skill = BundledSkills.get(seat.companion, name);
                o.addProperty("description", skill.description() == null ? "" : skill.description());
                o.addProperty("content", skill.content());
                com.google.gson.JsonArray files = new com.google.gson.JsonArray();
                BundledSkills.files(seat.companion, name).forEach(files::add);
                o.add("files", files);
            } else {
                o.addProperty("text", BundledSkills.file(seat.companion, name, file));
            }
        } catch (IllegalArgumentException refused) {
            throw new RpcException(RpcError.INVALID_PARAMS, refused.getMessage());
        }
        return o;
    }

    private JsonElement state(RpcPeer.Incoming in) throws Exception {
        Seat seat = seat(in);
        JsonObject o = new JsonObject();
        seats.onMain(() -> {
            o.addProperty("generation", seats.generation(seat.companion));
            seats.world().state(seat.companion).toJson().asMap().forEach(o::add);
            return null;
        });
        return o;
    }

    /** 客户端报:一次 run 开始了。服务端主线程上,按报来的先后,告诉插件({@code RUN_STARTED})。 */
    private void runStarted(JsonObject params) {
        UUID companion = seatedCompanion(params);
        if (companion != null) {
            long run = params.get("run").getAsLong();
            seats.world().main().execute(() -> com.dwinovo.numen.api.entity.CompanionEvents.fire(
                    com.dwinovo.numen.api.CompanionEvent.RUN_STARTED,
                    new com.dwinovo.numen.api.CompanionEvent.RunStarted(companion, run)));
        }
    }

    /** 客户端报:一次 run 收了。同 {@link #runStarted}。 */
    private void runEnded(JsonObject params) {
        UUID companion = seatedCompanion(params);
        if (companion != null) {
            long run = params.get("run").getAsLong();
            com.dwinovo.numen.api.CompanionEvent.Outcome outcome;
            try {
                outcome = com.dwinovo.numen.api.CompanionEvent.Outcome.valueOf(params.get("outcome").getAsString());
            } catch (IllegalArgumentException unknown) {
                Constants.LOG.warn("[numen-seat] {} reported an unknown run outcome {}", player, params.get("outcome"));
                return;
            }
            String reason = params.has("reason") ? params.get("reason").getAsString() : "";
            seats.world().main().execute(() -> com.dwinovo.numen.api.entity.CompanionEvents.fire(
                    com.dwinovo.numen.api.CompanionEvent.RUN_ENDED,
                    new com.dwinovo.numen.api.CompanionEvent.RunEnded(companion, run, outcome, reason)));
        }
    }

    /** 通知里的那只同伴,如果这条连接坐着她的座位;不是就记一行、不理。 */
    private UUID seatedCompanion(JsonObject params) {
        return seatedCompanion(params, "reported a run of");
    }

    private UUID seatedCompanion(JsonObject params, String what) {
        try {
            UUID companion = UUID.fromString(params.get("companion").getAsString());
            if (opened.containsKey(companion)) {
                return companion;
            }
            Constants.LOG.debug("[numen-seat] {} {} {}, whose seat it does not sit at", player, what, companion);
        } catch (RuntimeException unreadable) {
            Constants.LOG.warn("[numen-seat] dropped an unreadable run report from {}: {}", player, unreadable.toString());
        }
        return null;
    }

    private JsonElement say(RpcPeer.Incoming in) throws Exception {
        Seat seat = seat(in);
        String text = Params.string(in.params(), "text");
        Spoken spoken = Spoken.fromJson(Params.object(in.params(), "spoken"));
        boolean kept = seats.onMain(() -> {
            if (!seats.world().keeps(seat.companion, spoken.who())) {
                return false;
            }
            seats.world().say(seat.companion, text, spoken);
            return true;
        });
        if (!kept) {
            throw new RpcException(RpcError.INVALID_PARAMS, "params.spoken.who is not one of her keepers");
        }
        return new JsonObject();
    }
}
