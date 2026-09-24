package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.Schema;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 动作的一个参数:名字、类型、说明、必不必填。它是这个参数的唯一声明——命令行的写法、帮助里的一行、
 * 快捷工具 schema 里的字段、处理函数取值用的键,全从这里来。
 *
 * <p>必填的是位置参数,按声明顺序写在动作后面;可选的是标志,写成 {@code --name value},顺序随意
 * (见 {@link FlagsArgument})。名字就是 JSON 的键,也就是标志名,不另起一个。
 *
 * <pre>{@code
 * static final Param<String> TASK_ID = Param.optional("task_id", ArgType.word(), "What to cancel …");
 * ...
 * String id = args.get(TASK_ID);   // 没给是 null
 * }</pre>
 */
public record Param<T>(String name, ArgType<T> type, String description, boolean required) {

    /** 参数名与 JSON 键同形:小写字母开头,小写字母、数字、下划线。 */
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public Param {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("参数名不合规(小写字母开头,只含 [a-z0-9_]): '" + name + "'");
        }
        if (type == null) {
            throw new IllegalArgumentException("参数 " + name + " 没给类型");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("参数 " + name + " 没写说明——帮助和 schema 都从它来");
        }
        if (!required && type.restOfLine()) {
            throw new IllegalArgumentException("参数 " + name + " 吃掉余下整行,不能当可选标志");
        }
    }

    public static <T> Param<T> required(String name, ArgType<T> type, String description) {
        return new Param<>(name, type, description, true);
    }

    public static <T> Param<T> optional(String name, ArgType<T> type, String description) {
        return new Param<>(name, type, description, false);
    }

    /** 一组参数的 JSON schema,字段按声明顺序。快捷工具与 numen 工具的 schema 都经这里生成。 */
    static Map<String, Object> schemaOf(List<Param<?>> params) {
        Schema.Builder builder = Schema.object();
        for (Param<?> p : params) {
            p.type().addTo(builder, p.name(), p.description(), p.required());
        }
        return builder.build();
    }

    /** 命令行上的样子:位置参数 {@code <name>},吃整行的 {@code <name...>},标志 {@code [--name <类型>]}。 */
    String usage() {
        if (!required) {
            return "[--" + name + " <" + type.kind() + ">]";
        }
        return type.restOfLine() ? "<" + name + "...>" : "<" + name + ">";
    }
}
