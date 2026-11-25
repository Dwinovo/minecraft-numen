package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.skill.BundledSkills;
import com.dwinovo.numen.api.agent.skill.SkillInfo;
import com.dwinovo.numen.api.script.BuiltinModules;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import com.dwinovo.numen.mcp.rpc.RpcError;
import com.dwinovo.numen.script.ScriptEngine;
import com.dwinovo.numen.api.program.ClientTransport;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import com.dwinovo.numen.mcp.rpc.RpcPeer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 向客户端要她的模块时最多等多久。 */
    private static final java.time.Duration MODULES_TIMEOUT = java.time.Duration.ofSeconds(30);

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
        peer.onRequest(SeatMethods.MODULES_BUILTIN, this::builtinModule);
        peer.onRequest(SeatMethods.SKILL_READ, this::readSkill);
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

    /**
     * 向这条连接的客户端要她的模块:哈希到正文。在处理线程上等答复。
     *
     * @throws Exception 客户端没答、答错
     */
    Map<String, String> requestModules(UUID companion, List<String> hashes) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("companion", companion.toString());
        com.google.gson.JsonArray list = new com.google.gson.JsonArray();
        hashes.forEach(list::add);
        params.add("hashes", list);
        JsonObject answer = peer.call(SeatMethods.MODULES_GET, params, out, MODULES_TIMEOUT).getAsJsonObject();
        Map<String, String> got = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> module : answer.getAsJsonObject("modules").entrySet()) {
            got.put(module.getKey(), module.getValue().getAsString());
        }
        return got;
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

    private JsonElement run(RpcPeer.Incoming in) throws Exception {
        Seat seat = seat(in);
        JsonObject listed = Params.object(in.params(), "modules");
        Map<String, String> manifest = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> module : listed.entrySet()) {
            manifest.put(module.getKey(), module.getValue().getAsString());
        }
        Set<String> disabled = new java.util.TreeSet<>();
        if (in.params().get("disabled") instanceof com.google.gson.JsonArray names) {
            names.forEach(n -> disabled.add(n.getAsString()));
        }
        ProgramResult result = seat.run(this, Params.string(in.params(), "program"),
                Params.requiredInteger(in.params(), "generation"), Params.string(in.params(), "code"), manifest,
                disabled);
        return result.toJson();
    }

    private JsonElement builtinModule(RpcPeer.Incoming in) throws Exception {
        seat(in);
        JsonObject o = new JsonObject();
        BuiltinModules.Builtin builtin = BuiltinModules.get(Params.string(in.params(), "name"));
        if (builtin != null) {
            o.addProperty("code", builtin.code());
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

    private JsonElement say(RpcPeer.Incoming in) throws Exception {
        Seat seat = seat(in);
        String text = Params.string(in.params(), "text");
        seats.onMain(() -> {
            seats.world().say(seat.companion, text);
            return null;
        });
        return new JsonObject();
    }
}
