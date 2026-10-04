package com.dwinovo.numen.agent.script;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 API 调用交回的结果在线上的样子,写法与读法只在这里。执行的一侧(服务端或主人客户端)写,跑程序的一侧({@link ScriptCall})读;
 * 中间只是 JSON。值是 Lua 值的 JSON(表是对象、列表是数组,收起来的字段在 {@link JsonValues#FOLDED} 下),由登记处按函数的返回类型写出,
 * 这里不猜形状:
 *
 * <ul>
 *   <li>{@code {"ok": true, "value": …}}:当场或等了一会儿得到的值;不返回值的函数没有 {@code value}。</li>
 *   <li>{@code {"ok": true, "job": "t12"}}:占身体的活受理了,它的收尾是一条 task_finished,程序等那一条。</li>
 *   <li>{@code {"ok": false, "error": {"kind": …, "message": …, "hint": …, "data": …}}}:失败,错误值的字段见 {@link ScriptRun#failure}。</li>
 * </ul>
 * 一件活收尾时(task_finished 带的结果)是前一种或后一种,另带 {@code account}:它做了什么的实际账,整段写进程序的回执。等了一会儿
 * 才有的值(一件短身体活、主人点了头)同样可以带 {@code account}。
 */
public final class ApiReply {

    static final String OK = "ok";
    static final String VALUE = "value";
    static final String JOB = "job";
    static final String ERROR = "error";
    static final String ACCOUNT = "account";

    private ApiReply() {}

    /** 成功,值是 {@code value}(Lua 值的 JSON;不返回值是 null)。 */
    public static JsonObject value(JsonElement value) {
        JsonObject out = new JsonObject();
        out.addProperty(OK, true);
        if (value != null && !value.isJsonNull()) {
            out.add(VALUE, value);
        }
        return out;
    }

    /** 占身体的活受理了,编号 {@code task}。 */
    public static JsonObject job(String task) {
        JsonObject out = new JsonObject();
        out.addProperty(OK, true);
        out.addProperty(JOB, task);
        return out;
    }

    /**
     * 失败。
     *
     * @param hint 能照抄的下一行程序;没有是 null
     * @param data 失败时知道的东西(Lua 值的 JSON);没有是 null
     */
    public static JsonObject error(ErrorKind kind, String message, String hint, JsonElement data) {
        JsonObject error = new JsonObject();
        error.addProperty(ScriptRun.KIND, kind.wire());
        error.addProperty(ScriptRun.MESSAGE, message);
        if (hint != null) {
            error.addProperty(ScriptRun.HINT, hint);
        }
        if (data != null && !data.isJsonNull()) {
            error.add(ScriptRun.DATA, data);
        }
        JsonObject out = new JsonObject();
        out.addProperty(OK, false);
        out.add(ERROR, error);
        return out;
    }

    /** 一件活收尾时(或等到的值)的结果:{@code reply} 是 {@link #value} 或 {@link #error} 写的那一份,加上它的实际账。 */
    public static JsonObject ended(JsonObject reply, String account) {
        JsonObject out = reply.deepCopy();
        out.addProperty(ACCOUNT, account == null ? "" : account);
        return out;
    }

    /**
     * 读回来的一份结果。
     *
     * @param value   成功时的值(Lua 值:null、布尔、数、字符串、列表、名字到值的表);没有是 null
     * @param job     受理的活的编号;不是受理是 null
     * @param error   失败时的错误值(至少 {@code kind} 与 {@code message});成功是 null
     * @param account 收尾时的实际账;不是收尾是 null
     */
    public record Parsed(boolean ok, Object value, String job, Map<String, Object> error, String account) {}

    /**
     * 读一份结果。
     *
     * @throws IllegalArgumentException 不是这个样子:执行的一侧只经这里写,读不成是接线错了
     */
    public static Parsed parse(String json) {
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException notJson) {
            throw new IllegalArgumentException("not an API reply: " + json, notJson);
        }
        return parse(o);
    }

    /** 同 {@link #parse(String)},已经是 JSON 对象的。 */
    public static Parsed parse(JsonObject o) {
        if (!(o.get(OK) instanceof JsonElement ok) || !ok.isJsonPrimitive()) {
            throw new IllegalArgumentException("not an API reply: " + o);
        }
        String account = o.has(ACCOUNT) ? o.get(ACCOUNT).getAsString() : null;
        if (ok.getAsBoolean()) {
            return new Parsed(true, JsonValues.toJava(o.get(VALUE)), o.has(JOB) ? o.get(JOB).getAsString() : null,
                    null, account);
        }
        Map<String, Object> error = new LinkedHashMap<>();
        if (JsonValues.toJava(o.get(ERROR)) instanceof Map<?, ?> fields) {
            fields.forEach((k, v) -> error.put(String.valueOf(k), v));
        }
        if (!(error.get(ScriptRun.KIND) instanceof String)) {
            throw new IllegalArgumentException("an API error without a kind: " + o);
        }
        return new Parsed(false, null, null, error, account);
    }
}
