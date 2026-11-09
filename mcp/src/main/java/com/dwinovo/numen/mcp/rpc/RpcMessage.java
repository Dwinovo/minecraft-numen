package com.dwinovo.numen.mcp.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON-RPC 2.0 的一条消息:请求、通知或答复。消息与 JSON 之间的换算只在这里。
 *
 * <p>编号({@code id})是 JSON 的字符串或数字,原样保存为 {@link JsonPrimitive},答复照原样带回去;按值相等,可以作表的键。
 */
public sealed interface RpcMessage {

    String VERSION = "2.0";

    /** 要答复的请求。{@code params} 没有是空对象。 */
    record Request(JsonPrimitive id, String method, JsonObject params) implements RpcMessage {
        public Request {
            params = params == null ? new JsonObject() : params;
        }
    }

    /** 不答复的通知。{@code params} 没有是空对象。 */
    record Notification(String method, JsonObject params) implements RpcMessage {
        public Notification {
            params = params == null ? new JsonObject() : params;
        }
    }

    /**
     * 一个请求的答复:{@code result} 与 {@code error} 恰有一个。{@code id} 是 null 的只有收到的东西连编号都读不出来时的错误答复。
     */
    record Response(JsonPrimitive id, JsonElement result, RpcError error) implements RpcMessage {

        public static Response ok(JsonPrimitive id, JsonElement result) {
            return new Response(id, result, null);
        }

        public static Response failed(JsonPrimitive id, RpcError error) {
            return new Response(id, null, error);
        }
    }

    /** 写成 JSON 对象。 */
    default JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", VERSION);
        // Java 17 没有 switch 的类型模式:按密封的三种逐个判断,剩下的只能是答复
        if (this instanceof Request r) {
            o.add("id", r.id());
            o.addProperty("method", r.method());
            o.add("params", r.params());
        } else if (this instanceof Notification n) {
            o.addProperty("method", n.method());
            // 这一代游戏带的 Gson 是 2.10,JsonObject 还没有 isEmpty(2.10.1 才有)
            if (n.params().size() != 0) {
                o.add("params", n.params());
            }
        } else {
            Response r = (Response) this;
            o.add("id", r.id() == null ? JsonNull.INSTANCE : r.id());
            if (r.error() != null) {
                JsonObject e = new JsonObject();
                e.addProperty("code", r.error().code());
                e.addProperty("message", r.error().message());
                if (r.error().data() != null) {
                    e.add("data", r.error().data());
                }
                o.add("error", e);
            } else {
                o.add("result", r.result() == null ? new JsonObject() : r.result());
            }
        }
        return o;
    }

    /** 写成一行 JSON 文本(不含换行,stdio 与 SSE 的 data 都要求如此)。 */
    default String encode() {
        return toJson().toString();
    }

    /**
     * 读一段文本:一条消息,或(2025-03-26 允许的)一批消息组成的数组。
     *
     * @throws RpcException 不是 JSON({@link RpcError#PARSE_ERROR})或不是 JSON-RPC 消息({@link RpcError#INVALID_REQUEST})
     */
    static List<RpcMessage> decodeAll(String text) throws RpcException {
        JsonElement json;
        try {
            json = JsonParser.parseString(text);
        } catch (JsonParseException notJson) {
            throw new RpcException(RpcError.PARSE_ERROR, "Parse error: " + notJson.getMessage());
        }
        List<RpcMessage> out = new ArrayList<>();
        if (json instanceof JsonArray batch) {
            if (batch.isEmpty()) {
                throw new RpcException(RpcError.INVALID_REQUEST, "Invalid Request: empty batch");
            }
            for (JsonElement one : batch) {
                out.add(of(one));
            }
        } else {
            out.add(of(json));
        }
        return out;
    }

    /** 读一条消息(不收数组)。 */
    static RpcMessage decode(String text) throws RpcException {
        List<RpcMessage> all = decodeAll(text);
        if (all.size() != 1 || text.stripLeading().startsWith("[")) {
            throw new RpcException(RpcError.INVALID_REQUEST, "Invalid Request: a batch is not allowed here");
        }
        return all.get(0);
    }

    /** 一个 JSON 值读成消息。 */
    static RpcMessage of(JsonElement json) throws RpcException {
        if (!(json instanceof JsonObject o) || !o.has("jsonrpc") || !VERSION.equals(string(o.get("jsonrpc")))) {
            throw new RpcException(RpcError.INVALID_REQUEST, "Invalid Request: not a JSON-RPC 2.0 message");
        }
        JsonElement id = o.get("id");
        if (id != null && !id.isJsonNull() && !(id instanceof JsonPrimitive p && (p.isString() || p.isNumber()))) {
            throw new RpcException(RpcError.INVALID_REQUEST, "Invalid Request: id must be a string or a number");
        }
        JsonPrimitive idValue = id instanceof JsonPrimitive p ? canonicalId(p) : null;
        String method = string(o.get("method"));
        JsonObject params = o.get("params") instanceof JsonObject p ? p : null;
        if (o.has("params") && params == null && !o.get("params").isJsonNull()) {
            throw new RpcException(RpcError.INVALID_REQUEST, "Invalid Request: params must be an object");
        }
        if (method != null) {
            return idValue == null ? new Notification(method, params) : new Request(idValue, method, params);
        }
        if (o.get("error") instanceof JsonObject e) {
            int code = e.get("code") instanceof JsonPrimitive c && c.isNumber() ? c.getAsInt() : RpcError.INTERNAL_ERROR;
            String message = string(e.get("message"));
            return Response.failed(idValue, new RpcError(code, message == null ? "" : message, e.get("data")));
        }
        if (o.has("result") && idValue != null) {
            return Response.ok(idValue, o.get("result"));
        }
        throw new RpcException(RpcError.INVALID_REQUEST, "Invalid Request: neither a request, a notification nor a response");
    }

    /**
     * 整数编号统一成 {@code long}:Gson 读出来的数字与我们自己用 {@code long} 造的编号值相等、哈希却不同,不统一的话答复在表里找不到
     * 它的请求。写回去仍是同一个数。
     */
    static JsonPrimitive canonicalId(JsonPrimitive id) {
        if (id.isNumber()) {
            java.math.BigDecimal value = id.getAsBigDecimal();
            if (value.stripTrailingZeros().scale() <= 0 && value.toBigInteger().bitLength() < 64) {
                return new JsonPrimitive(value.longValueExact());
            }
        }
        return id;
    }

    private static String string(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }
}
