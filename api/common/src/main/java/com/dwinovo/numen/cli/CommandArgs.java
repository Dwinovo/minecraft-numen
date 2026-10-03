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

    /** 同一份参数,{@code param} 换成 {@code value}:受理时把一个值换成它稳定的写法再写回一行(见 {@link #write})。 */
    public <T> CommandArgs with(Param<T> param, T value) {
        Map<String, Object> out = new LinkedHashMap<>(values);
        out.put(param.name(), value);
        return new CommandArgs(out);
    }

    /**
     * 命令行这一侧:位置参数按名字从 Brigadier 的上下文里取(可以不写的那一个,没写就不在),标志是 {@link FlagsArgument}
     * 已经读好的那张表(这一行没写标志就是空表)。
     */
    static CommandArgs fromCommand(List<Param<?>> positionals, CommandContext<?> ctx, Map<String, Object> flags) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Param<?> p : positionals) {
            if (p.required() || ctx.getNodes().stream().anyMatch(n -> n.getNode().getName().equals(p.name()))) {
                out.put(p.name(), ctx.getArgument(p.name(), Object.class));
            }
        }
        out.putAll(flags);
        return new CommandArgs(out);
    }

    /**
     * 快捷工具这一侧:每个声明过的参数按名字取 JSON 值,经它的类型读成值;JSON {@code null} 与没给同义。键里的 {@code -} 与
     * {@code _} 是同一个字符,和命令行上的标志名同一条规矩({@link Param#nameOf})。
     * 没声明的键拒掉——命令行上写错的标志也是拒,两个入口认的是同一张参数表。
     *
     * @throws BadArgument 参数不对:哪个参数、错在哪;看得出想写什么时带上照这一种写法该写成的值。服务端的 {@code serve} 与脚本的
     *                     前端都把它变成 {@code bad_argument} 的失败
     */
    static CommandArgs fromJson(List<Param<?>> params, JsonObject given) {
        JsonObject json = new JsonObject();
        for (String key : given.keySet()) {
            json.add(Param.nameOf(key), given.get(key));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Param<?> p : params) {
            JsonElement value = json.get(p.name());
            if (value == null || value.isJsonNull()) {
                if (p.required()) {
                    throw new BadArgument(p, "argument '" + p.name() + "' is missing (or nil)", null);
                }
                continue;
            }
            try {
                out.put(p.name(), p.type().fromJson(value));
            } catch (CommandSyntaxException e) {
                // 一个值单独读,读到第几个字符没有意义:只说哪个参数、错在哪
                throw new BadArgument(p, "argument '" + p.name() + "': " + e.getRawMessage().getString(), null);
            } catch (ArgType.WrongShape wrong) {
                throw new BadArgument(p, "argument '" + p.name() + "': " + wrong.getMessage(), wrong.instead);
            }
        }
        for (String key : json.keySet()) {
            if (params.stream().noneMatch(p -> p.name().equals(key))) {
                throw new BadArgument(null, "unknown argument '" + key + "'; this takes: "
                        + (params.isEmpty() ? "no arguments"
                            : params.stream().map(Param::name).collect(Collectors.joining(", "))), null);
            }
        }
        return new CommandArgs(out);
    }

    /**
     * 参数读不成:哪个参数(说不上是哪一个的是 null)、错在哪、看得出想写什么时照这一种写法该写成的那个值(脚本里的值,没有是 null)。
     */
    static final class BadArgument extends IllegalArgumentException {

        final transient Param<?> param;
        final transient Object instead;

        BadArgument(Param<?> param, String message, Object instead) {
            super(message);
            this.param = param;
            this.instead = instead;
        }
    }

    /**
     * 这些参数写回一行命令:{@code path} 之后是写了值的位置参数,再是写了值的标志 {@code --name value}(开关写 {@code --name}
     * 或 {@code --no-name}),都按 {@code params} 的顺序,值写成它在命令行上的样子({@link ArgType} 的写法)。这一行交给同一棵树读回来,得到的是相等的
     * 一份参数——读与写是同一张参数表的两个方向。{@code params} 里没列的参数(比如只管这次调用落到哪儿的标志)不写。
     *
     * @param path 这一行的动作路径,如 {@code build layer}
     */
    public String write(String path, List<Param<?>> params) {
        StringBuilder line = new StringBuilder(path);
        for (Param<?> p : params) {
            if (p.positional() && (p.required() || values.containsKey(p.name()))) {
                line.append(' ').append(written(p));
            }
        }
        for (Param<?> p : params) {
            if (p.positional() || !values.containsKey(p.name())) {
                continue;
            }
            if (p.type().isSwitch()) {
                line.append(' ').append(FlagsArgument.PREFIX).append(Boolean.TRUE.equals(values.get(p.name()))
                        ? p.flag() : Param.NO.replace('_', '-') + p.flag());
            } else {
                line.append(' ').append(FlagsArgument.PREFIX).append(p.flag()).append(' ').append(written(p));
            }
        }
        return line.toString();
    }

    /**
     * 这些参数写成脚本里的一次调用:{@code path} 的函数,按 {@code params} 的顺序先是写了值的对象,再是写了值的选项表,值是脚本里
     * 的样子({@link ArgType#plain})。回执、征询与提示里点名一次调用都这样写,她照抄就是一次能跑的调用;脚本这个前端读回来是同一份
     * 参数。{@code params} 里没列的参数不写。
     *
     * @param path 这次调用的动作路径,如 {@code area delete}
     */
    public String call(String path, List<Param<?>> params) {
        String[] words = path.split(" ", 2);
        List<Object> objects = new java.util.ArrayList<>();
        Map<String, Object> options = new LinkedHashMap<>();
        for (Param<?> p : params) {
            if (!values.containsKey(p.name())) {
                continue;
            }
            Object plain = plain(p);
            if (p.positional() && plain instanceof List<?> several && p.type().span() == ArgType.Span.SEVERAL) {
                objects.addAll(several);
            } else if (p.positional()) {
                objects.add(plain);
            } else {
                options.put(p.name(), plain);
            }
        }
        return com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.call(
                com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.function(words[0], words[1]), objects, options);
    }

    /** 这些参数里写了值的选项写成脚本里的一张选项表:{@code {arrive = "dig"}};一个都没写是 {@code {}}。 */
    public String options(List<Param<?>> params) {
        return com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.table(optionValues(params));
    }

    /** 这些参数里写了值的选项,名字到脚本里的值({@link ArgType#plain}),按 {@code params} 的顺序。 */
    public Map<String, Object> optionValues(List<Param<?>> params) {
        Map<String, Object> options = new LinkedHashMap<>();
        for (Param<?> p : params) {
            if (values.containsKey(p.name()) && !p.positional()) {
                options.put(p.name(), plain(p));
            }
        }
        return options;
    }

    private <T> Object plain(Param<T> param) {
        return param.type().plain(get(param));
    }

    private <T> String written(Param<T> param) {
        T value = get(param);
        if (value == null) {
            throw new IllegalArgumentException("参数 " + param.name() + " 没有值,写不回命令行");
        }
        return param.type().write(value);
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
