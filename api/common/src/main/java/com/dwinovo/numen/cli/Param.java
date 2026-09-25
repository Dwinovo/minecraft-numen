package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.Schema;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 动作的一个参数:名字、类型、说明、必不必填,以及两条取值提示。它是这个参数的唯一声明——命令行的写法、帮助里的
 * 一行、快捷工具 schema 里的字段、处理函数取值用的键,全从这里来。
 *
 * <p>必填的是位置参数,按声明顺序写在动作后面;可选的是标志,写成 {@code --name value},顺序随意
 * (见 {@link FlagsArgument})。名字就是 JSON 的键,也就是标志名,不另起一个。
 *
 * <h2>取值提示</h2>
 * 类型自己说得出的(整数的范围、布尔的 true/false)由 {@link ArgType} 说;类型说不出的由声明补上:
 * <ul>
 *   <li>{@link #values}:能写哪些值——固定的几个就列出来,不固定的写明去哪查(哪条命令、哪个事件给出它);</li>
 *   <li>{@link #whenOmitted}:可选参数不写时会怎样,写成 "Omit to …" 的后半句。</li>
 * </ul>
 * 两条都接在说明后面({@link #explained}),帮助与 schema 读的是同一段文字。
 *
 * <pre>{@code
 * static final Param<String> TEXTURE = Param.optional("texture", ArgType.string(), "Which texture to wear.")
 *         .values("a texture id from the textures numen ysm options lists")
 *         .whenOmitted("use the model's first texture");
 * ...
 * String texture = args.get(TEXTURE);   // 没给是 null
 * }</pre>
 *
 * @param values      能写哪些值、去哪查;没写是 null
 * @param whenOmitted 可选参数不写时会怎样("Omit to" 后面那半句);必填参数与没写的都是 null
 */
public record Param<T>(String name, ArgType<T> type, String description, boolean required,
                       String values, String whenOmitted) {

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
        if (values != null && values.isBlank()) {
            throw new IllegalArgumentException("参数 " + name + " 的取值提示是空的");
        }
        if (whenOmitted != null && required) {
            throw new IllegalArgumentException("参数 " + name + " 是必填的,没有\"不写时\"");
        }
        if (whenOmitted != null && whenOmitted.isBlank()) {
            throw new IllegalArgumentException("参数 " + name + " 不写时会怎样是空的");
        }
    }

    public static <T> Param<T> required(String name, ArgType<T> type, String description) {
        return new Param<>(name, type, description, true, null, null);
    }

    public static <T> Param<T> optional(String name, ArgType<T> type, String description) {
        return new Param<>(name, type, description, false, null, null);
    }

    /** 能写哪些值、去哪查,例如 {@code "pot or stockpot"}、{@code "a model id as numen ysm options lists it"}。 */
    public Param<T> values(String values) {
        return new Param<>(name, type, description, required, values, whenOmitted);
    }

    /** 可选参数不写时会怎样,接在 "Omit to" 后面,例如 {@code "use the model's first texture"}。 */
    public Param<T> whenOmitted(String whenOmitted) {
        return new Param<>(name, type, description, required, values, whenOmitted);
    }

    /** 一组参数的 JSON schema,字段按声明顺序。快捷工具与 command 工具的 schema 都经这里生成。 */
    static Map<String, Object> schemaOf(List<Param<?>> params) {
        Schema.Builder builder = Schema.object();
        for (Param<?> p : params) {
            p.type().addTo(builder, p.name(), p.explained(), p.required());
        }
        return builder.build();
    }

    /** 说明接上取值提示:帮助里这个参数的那句话,也是 schema 里这个字段的描述。 */
    String explained() {
        StringBuilder sb = new StringBuilder(description);
        if (values != null) {
            sb.append(" Values: ").append(values).append('.');
        }
        if (whenOmitted != null) {
            sb.append(" Omit to ").append(whenOmitted).append('.');
        }
        return sb.toString();
    }

    /** 命令行上的样子:位置参数 {@code <name>},吃整行的 {@code <name...>},标志 {@code [--name <类型>]}。 */
    String usage() {
        if (!required) {
            return "[--" + name + " <" + type.kind() + ">]";
        }
        return type.restOfLine() ? "<" + name + "...>" : "<" + name + ">";
    }
}
