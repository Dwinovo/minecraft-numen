package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ScriptEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一次调用写成脚本里的那一行:失败的 {@code hint}(能照抄的下一步)、征询里点名这次调用、重启后再跑的那一行都经这里,值经同一套值转换
 * ({@link LuaCodecs})写成字面量,不手拼字符串。
 *
 * <pre>{@code
 * throw new ApiError(ErrorKind.OUT_OF_REACH, "too far", Call.of("numen.move.to", pos, Map.of("arrive", "dig")));
 * }</pre>
 */
public final class Call {

    private Call() {}

    /** {@code fn(a, b, …)}:每个值按它的类型写成字面量(一张 {@code Map} 是一张表,选项表也是)。 */
    public static String of(String fn, Object... args) {
        List<Object> values = new ArrayList<>();
        for (Object a : args) {
            values.add(LuaCodecs.encode(a));
        }
        return ScriptEngine.IN_USE.call(fn, values, Map.of());
    }

    /** 一个函数的一次调用,按它的参数表写:位置参数依次(收下余下全部的逐个写出),写了值的选项合成最后一张选项表。 */
    public static String of(ApiFunction fn, Record args) {
        Map<String, Object> named = fn.encode(args);
        List<Object> objects = new ArrayList<>();
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        for (ApiFunction.Param p : fn.params()) {
            if (!named.containsKey(p.name())) {
                continue;
            }
            Object v = named.get(p.name());
            switch (p.role()) {
                case REST -> objects.addAll((List<?>) v);
                case OPTION -> options.put(p.name(), v);
                default -> objects.add(v);
            }
        }
        return ScriptEngine.IN_USE.call(fn.fullName(), objects, options);
    }

    /** 一个函数的全部帮助怎么要,一行能照抄的程序:{@code print(numen.api.help("numen.work.dig"))}。 */
    public static String help(String name) {
        return "print(" + of("numen.api.help", name) + ")";
    }
}
