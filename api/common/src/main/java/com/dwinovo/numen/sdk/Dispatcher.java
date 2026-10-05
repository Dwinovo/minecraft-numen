package com.dwinovo.numen.sdk;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.agent.script.JsonValues;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 一次调用从脚本到函数再回来:读参数、按端执行、按种类交回。
 *
 * <ol>
 *   <li>{@link #invocation}(跑程序的一侧):按顺序的对象与选项表按函数的参数表读成参数 record,读不成在调用处报
 *       {@code bad_argument}(哪个参数、要什么、给了什么;看得出想写什么时下一步是改好的那一整行),不派出去;读好的参数写回规范的脚本值,
 *       就是这次调用的 {@link Invocation}。</li>
 *   <li>{@link #client}(主人客户端,程序在服务端跑时由反向请求送来):客户端函数在那里执行。</li>
 *   <li>{@link #serve}(服务端):同一张参数表、同一套值转换再读一遍,交给函数。</li>
 * </ol>
 * 交回按函数的返回类型(登记时定):当场的值、等到的值({@link Pending})、占身体的活({@link Job},受理回活的编号,收尾经
 * task_finished 交回值与实际账,见 {@link #ended})。线上的样子只在 {@link ApiReply}。函数抛的 {@link ApiError} 是这次调用的失败;别的异常
 * 是函数的错,记日志,这次调用以 {@code failed} 结束——每次调用恰好一个结果。
 */
public final class Dispatcher {

    private Dispatcher() {}

    /**
     * 脚本里的一次调用读成函数与参数。
     *
     * @throws ApiError 没有这个函数({@code no_function});参数读不成({@code bad_argument})
     */
    public static Invocation invocation(ScriptRun.Call call) {
        ApiFunction fn = ApiRegistry.function(call.group() + "." + call.name());
        if (fn == null) {
            throw new ApiError(ErrorKind.NO_FUNCTION, "there is no API function " + call.function(),
                    "the <api> index lists every group; " + Call.help(call.group()) + " lists one.");
        }
        Record args = read(fn, call);
        return new Invocation(fn.groupName(), fn.name(), fn.fullName(),
                (JsonObject) JsonValues.toJson(fn.encode(args)));
    }

    /** 读参数;读不成是一次 {@code bad_argument},下一步是改好的那一整行(看得出时)或这个函数的帮助。 */
    private static Record read(ApiFunction fn, ScriptRun.Call call) {
        try {
            return fn.decode(fn.named(call.args(), call.options()));
        } catch (ApiFunction.BadArgument bad) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, bad.getMessage() + "\nusage: " + ApiDocs.usage(fn),
                    corrected(fn, call, bad));
        }
    }

    /**
     * 参数错了的下一步:看得出她想写什么(三个数想写一格)而那是一个对象或一个选项时,是改好的那一整行调用;否则是怎么看这个函数的全部
     * 帮助。
     */
    private static String corrected(ApiFunction fn, ScriptRun.Call call, ApiFunction.BadArgument bad) {
        ApiFunction.Param p = bad.param();
        if (bad.instead() == null || p == null) {
            return Call.help(fn.fullName());
        }
        int index = fn.positionals().indexOf(p);
        if (index >= 0 && index < call.args().size() && p.role() != ApiFunction.Role.REST) {
            List<Object> objects = new ArrayList<>(call.args());
            objects.set(index, bad.instead());
            return ScriptEngine.IN_USE.call(call.function(), objects, call.options());
        }
        if (p.role() == ApiFunction.Role.OPTION) {
            Map<String, Object> options = new LinkedHashMap<>(call.options());
            options.put(p.name(), bad.instead());
            return ScriptEngine.IN_USE.call(call.function(), call.args(), options);
        }
        if (p.role() == ApiFunction.Role.REST && call.args().size() == 1 + index) {
            List<Object> objects = new ArrayList<>(call.args());
            objects.set(index, bad.instead());
            return ScriptEngine.IN_USE.call(call.function(), objects, call.options());
        }
        return "write each of " + p.name() + " like " + ScriptEngine.IN_USE.value(bad.instead()) + ". "
                + Call.help(fn.fullName());
    }

    /** 这次调用是不是在服务端执行。 */
    public static boolean runsOnServer(Invocation invocation) {
        return function(invocation).side() == ApiFunction.Side.SERVER;
    }

    /**
     * 主人客户端执行一个客户端函数:{@code function} 是函数的全名,{@code args} 是读好的参数。没有这个客户端函数、参数读不成,都是一条
     * 失败。结果经 {@code reply} 恰好回一次。
     */
    public static void client(String function, JsonObject args, UUID companion, Consumer<String> reply) {
        ApiFunction fn = ApiRegistry.function(function);
        if (fn == null || fn.side() != ApiFunction.Side.CLIENT) {
            reply.accept(ApiReply.error(ErrorKind.NO_FUNCTION, "there is no API function " + function
                    + " on the client", null, null).toString());
            return;
        }
        Record values = decodeOrReply(fn, args, reply);
        if (values == null && fn.argsType() != null) {
            return;
        }
        run(fn, () -> fn.invoke(new ClientCall(companion, fn, values), values), null, null, null, reply);
    }

    /**
     * 服务端执行一次调用:{@code function} 是函数的全名,{@code args} 是读好的参数。没有这个服务端函数、参数读不成,都是一条失败。结果经
     * {@code reply} 恰好回一次。
     */
    public static void serve(String function, JsonObject args, NumenPlayer her, String callId, Consumer<String> reply) {
        ApiFunction fn = ApiRegistry.function(function);
        if (fn == null || fn.side() != ApiFunction.Side.SERVER) {
            reply.accept(ApiReply.error(ErrorKind.NO_FUNCTION, "there is no API function " + function
                    + " on the server", null, null).toString());
            return;
        }
        Record values = decodeOrReply(fn, args, reply);
        if (values == null && fn.argsType() != null) {
            return;
        }
        run(fn, () -> fn.invoke(new ServerCall(her, fn, values, callId), values), her, values, callId, reply);
    }

    /** 这次调用写成脚本里的那一行:失败的下一步是同一行再来一次。 */
    public static String lua(Invocation invocation) {
        ApiFunction fn = function(invocation);
        return Call.of(fn, fn.decode(named(invocation.args())));
    }

    /** 线上的参数(参数名 → Lua 值的 JSON)读成参数名 → 值。 */
    private static Map<String, Object> named(JsonObject args) {
        Map<String, Object> named = new LinkedHashMap<>();
        if (JsonValues.toJava(args) instanceof Map<?, ?> map) {
            map.forEach((k, v) -> named.put(String.valueOf(k), v));
        }
        return named;
    }

    /** 线上的参数读成参数 record;读不成回一条 {@code bad_argument},返回 null。 */
    private static Record decodeOrReply(ApiFunction fn, JsonObject args, Consumer<String> reply) {
        try {
            return fn.decode(named(args));
        } catch (ApiFunction.BadArgument bad) {
            reply.accept(ApiReply.error(ErrorKind.BAD_ARGUMENT, bad.getMessage() + "\nusage: " + ApiDocs.usage(fn),
                    Call.help(fn.fullName()), null).toString());
            return null;
        }
    }

    /** 执行一个函数的那一步:它的返回按种类交回。 */
    @FunctionalInterface
    private interface Body {
        Object run();
    }

    private static void run(ApiFunction fn, Body body, NumenPlayer her, Record args, String callId,
                            Consumer<String> reply) {
        Object result;
        try {
            result = body.run();
        } catch (ApiError failed) {
            reply.accept(error(failed).toString());
            return;
        } catch (IllegalArgumentException wrong) {
            // 函数说这些参数在此刻不成立(一个写错的方块状态、一个转不出的度数):和读不成同一种失败
            reply.accept(ApiReply.error(ErrorKind.BAD_ARGUMENT, wrong.getMessage() + "\nusage: " + ApiDocs.usage(fn),
                    Call.help(fn.fullName()), null).toString());
            return;
        } catch (RuntimeException broke) {
            Constants.LOG.error("[numen-api] {} broke", fn.fullName(), broke);
            reply.accept(ApiReply.error(ErrorKind.FAILED, fn.fullName() + " broke: " + broke, null, null).toString());
            return;
        }
        switch (fn.kind()) {
            case VALUE -> reply.accept(value(fn, result).toString());
            case PENDING -> {
                Pending<?> pending = (Pending<?>) result;
                pending.whenDone(v -> reply.accept(withStderr(value(fn, v), pending.stderr()).toString()),
                        failed -> reply.accept(error(failed).toString()));
            }
            case JOB -> accept(fn, (Job<?>) result, her, args, reply);
        }
    }

    /**
     * 受理一件占身体的活:准备过了才受理、才顶掉手上那件,回它的编号;准备不过当场失败。重启后再跑的是这次调用写成的那一行 Lua
     * (活换过参数的,是换过的那一份)。
     */
    private static void accept(ApiFunction fn, Job<?> job, NumenPlayer her, Record args, Consumer<String> reply) {
        TaskRecord record = job.record();
        if (record == null) {
            reply.accept(value(fn, job.done()).toString());
            return;
        }
        record.calledAs(fn);
        String replay = Call.of(fn, job.replay() != null ? job.replay() : args);
        TaskDispatch.setTask(her, record, replay, refused -> reply.accept(failure(refused).toString()),
                () -> reply.accept(ApiReply.job(record.publicId()).toString()));
    }

    /** 等到的值连同 API 对这次调用的报告;没什么可报告的就只是值。 */
    private static JsonObject withStderr(JsonObject reply, String stderr) {
        return stderr.isEmpty() ? reply : ApiReply.withStderr(reply, stderr);
    }

    /** 成功,值按函数的返回类型写成脚本的值。 */
    private static JsonObject value(ApiFunction fn, Object value) {
        Object lua;
        try {
            lua = fn.returns().encode(value);
        } catch (ClassCastException wrongType) {
            throw new IllegalStateException(fn.fullName() + " returned a " + value.getClass().getName()
                    + ", not what its signature says", wrongType);
        }
        return ApiReply.value(JsonValues.toJson(lua));
    }

    /** 函数说的失败。 */
    private static JsonObject error(ApiError failed) {
        JsonElement data = failed.data() == null ? null : JsonValues.toJson(LuaCodecs.encode(failed.data()));
        return ApiReply.error(failed.kind(), failed.getMessage(), failed.hint(), data);
    }

    /** 一件活或它的准备说的失败。 */
    private static JsonObject failure(TaskResult result) {
        JsonElement data = result.value() == null ? null : JsonValues.toJson(LuaCodecs.encode(result.value()));
        return ApiReply.error(result.kind(), result.message(), result.hint(), data);
    }

    /**
     * 一件活收尾时交回的结果:成功是它的值(按派它的函数的返回类型写),失败是它说的失败。不出自 API 函数的活({@code fn} 是 null)没有值。
     * task_finished 带的就是它;它的实际账是事件的正文,不在这里重复。
     */
    public static JsonObject ended(TaskResult result, ApiFunction fn) {
        return result.success()
                ? (fn == null ? ApiReply.value(null) : value(fn, result.value()))
                : failure(result);
    }

    private static ApiFunction function(Invocation invocation) {
        ApiFunction fn = ApiRegistry.function(invocation.function());
        if (fn == null) {
            throw new IllegalStateException("there is no API function " + invocation.function());
        }
        return fn;
    }
}
