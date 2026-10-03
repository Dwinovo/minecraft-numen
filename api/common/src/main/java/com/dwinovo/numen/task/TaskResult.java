package com.dwinovo.numen.task;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * 一次 API 调用(或一件身体活)交回的结果:成没成、给她看的那句话、交给脚本的数据,失败时还有种类与能照抄的下一步。
 *
 * <h2>数据与文字</h2>
 * 脚本拿到的是 {@code data}(换成 Lua 的表);{@code message} 只进整段程序的回执与 task_finished,给她读。两样由同一个处理函数
 * 在同一处、从同一份事实写出:数据里有的,话里不另编一份;话里说到的位置、数目,数据里有对应的键。键是小写下划线,位置一律是
 * {@code Shapes} 的 Pos 写法。
 *
 * <h2>失败</h2>
 * 失败时 {@code kind} 说是哪一类({@link ErrorKind}),脚本 {@code pcall} 后按它分支;{@code hint} 是能照抄的下一行程序。
 * 期限到了是 {@link ErrorKind#TIMEOUT},被叫停是 {@link ErrorKind#INTERRUPTED}。
 *
 * @param success     做成了没有
 * @param message     给她读的那句话
 * @param timedOut    到了期限
 * @param interrupted 被叫停
 * @param data        交给脚本的数据;没有是空表。值能被 Gson 写成 JSON
 * @param kind        失败的种类;成功是 null
 * @param hint        失败时能照抄的下一步;没有是 null
 */
public record TaskResult(boolean success,
                         String message,
                         boolean timedOut,
                         boolean interrupted,
                         Map<String, Object> data,
                         ErrorKind kind,
                         String hint) {

    private static final Gson GSON = new Gson();

    public TaskResult {
        if (success == (kind != null)) {
            throw new IllegalArgumentException("a failed result names its kind, a successful one has none");
        }
    }

    public static TaskResult ok(String message, Map<String, Object> data) {
        return new TaskResult(true, message, false, false, data, null, null);
    }

    public static TaskResult ok(String message) {
        return ok(message, Map.of());
    }

    /** 成功,数据是一份 JSON 对象(查询按 {@code Shapes} 的写法造好的那一份)。 */
    public static TaskResult ok(String message, JsonObject data) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        data.entrySet().forEach(e -> out.put(e.getKey(), e.getValue()));
        return ok(message, out);
    }

    public static TaskResult fail(String message, Map<String, Object> data) {
        return fail(ErrorKind.FAILED, message, null, data);
    }

    public static TaskResult fail(String message) {
        return fail(ErrorKind.FAILED, message, null, Map.of());
    }

    /** 失败,说清种类;{@code hint} 是能照抄的下一步(没有为 null)。 */
    public static TaskResult fail(ErrorKind kind, String message, String hint) {
        return fail(kind, message, hint, Map.of());
    }

    /** 失败,说清种类,带上失败时的数据(够不着的最近一格这类)。 */
    public static TaskResult fail(ErrorKind kind, String message, String hint, Map<String, Object> data) {
        return new TaskResult(false, message, false, false, data, kind, hint);
    }

    public static TaskResult timeout(String message, Map<String, Object> data) {
        return new TaskResult(false, message, true, false, data, ErrorKind.TIMEOUT, null);
    }

    public static TaskResult timeout(String message) {
        return timeout(message, Map.of());
    }

    public static TaskResult cancelled(String message, Map<String, Object> data) {
        return new TaskResult(false, message, false, true, data, ErrorKind.INTERRUPTED, null);
    }

    public static TaskResult cancelled(String message) {
        return cancelled(message, Map.of());
    }

    /** 同一份结果,消息前面写上是谁叫停的({@link TaskRecord.StopCause})。 */
    public TaskResult stoppedBy(TaskRecord.StopCause cause) {
        return new TaskResult(success, cause.words() + " — " + message, timedOut, interrupted, data, kind, hint);
    }

    /**
     * 写成线上的 JSON:{@code success}、{@code message},失败时 {@code kind} 与 {@code hint},有数据时 {@code data}。
     */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("success", success);
        root.addProperty("message", message == null ? "" : message);
        if (timedOut) root.addProperty("timed_out", true);
        if (interrupted) root.addProperty("interrupted", true);
        if (!success) {
            root.addProperty("kind", kind.wire());
            if (hint != null) root.addProperty("hint", hint);
        }
        if (data != null && !data.isEmpty()) {
            JsonObject dataObj = new JsonObject();
            for (Map.Entry<String, Object> e : data.entrySet()) {
                Object v = e.getValue();
                if (v instanceof com.google.gson.JsonElement json) dataObj.add(e.getKey(), json);
                else if (v instanceof Number n) dataObj.addProperty(e.getKey(), n);
                else if (v instanceof Boolean b) dataObj.addProperty(e.getKey(), b);
                // 列表/映射按 JSON 展开:塞进去的结构必须以结构的样子到达脚本,落成 Java 的 toString 就成了读不动的 [{k=v}]
                else if (v instanceof java.util.Collection<?> || v instanceof Map<?, ?>) {
                    dataObj.add(e.getKey(), GSON.toJsonTree(v));
                } else if (v != null) dataObj.addProperty(e.getKey(), v.toString());
            }
            root.add("data", dataObj);
        }
        return root.toString();
    }
}
