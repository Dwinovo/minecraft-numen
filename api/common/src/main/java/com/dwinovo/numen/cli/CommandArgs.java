package com.dwinovo.numen.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一次调用读好的参数值,交给处理函数。两个入口各造一份:命令行由 Brigadier 读位置参数、{@link FlagsArgument}
 * 读标志;快捷工具从 JSON 按键读。两边的值都出自 {@link ArgType} 的同一个读法,所以同一件事从哪个入口进来,
 * 处理函数拿到的是相等的一份。
 */
public final class CommandArgs {

    private final Map<String, Object> values;

    private CommandArgs(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    /** 这个参数的值;可选参数没给是 {@code null}。 */
    @SuppressWarnings("unchecked")
    public <T> T get(Param<T> param) {
        return (T) values.get(param.name());
    }

    /**
     * 命令行这一侧:位置参数按名字从 Brigadier 的上下文里取,标志是 {@link FlagsArgument} 已经读好的那张表
     * (这一行没写标志就是空表)。
     */
    static CommandArgs fromCommand(List<Param<?>> positionals, CommandContext<?> ctx, Map<String, Object> flags) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Param<?> p : positionals) {
            out.put(p.name(), ctx.getArgument(p.name(), Object.class));
        }
        out.putAll(flags);
        return new CommandArgs(out);
    }

    /**
     * 快捷工具这一侧:每个声明过的参数按名字取 JSON 值,经它的类型读成值;JSON {@code null} 与没给同义。
     * 没声明的键拒掉——命令行上写错的标志也是拒,两个入口认的是同一张参数表。
     *
     * @throws IllegalArgumentException 参数不对——工具契约里"参数不对"的信号,服务端的 {@code serve} 与客户端的
     *                                  派发器都把它变成一条模型读得懂的失败回执
     */
    static CommandArgs fromJson(List<Param<?>> params, JsonObject json) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Param<?> p : params) {
            JsonElement value = json.get(p.name());
            if (value == null || value.isJsonNull()) {
                if (p.required()) throw new IllegalArgumentException("missing required argument: " + p.name());
                continue;
            }
            try {
                out.put(p.name(), p.type().fromJson(value));
            } catch (CommandSyntaxException e) {
                throw new IllegalArgumentException("argument '" + p.name() + "': " + e.getMessage());
            }
        }
        for (String key : json.keySet()) {
            if (params.stream().noneMatch(p -> p.name().equals(key))) {
                throw new IllegalArgumentException("unknown argument '" + key + "'; this takes: "
                        + (params.isEmpty() ? "no arguments"
                            : params.stream().map(Param::name).collect(Collectors.joining(", "))));
            }
        }
        return new CommandArgs(out);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CommandArgs other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
