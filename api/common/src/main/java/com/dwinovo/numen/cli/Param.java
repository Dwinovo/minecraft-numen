package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.Schema;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 动作的一个参数:名字、类型、说明、怎么写,以及两条取值提示。它是这个参数的唯一声明——脚本里的写法、一行命令的写法、帮助里的
 * 一行、处理函数取值用的键,全从这里来。
 *
 * <h2>三种写法</h2>
 * <ul>
 *   <li>{@link #required}:按顺序的对象,写在函数的括号里(一行命令里写在动作后面)——它就是这次调用操作的东西;</li>
 *   <li>{@link #optionalPositional}:可以不写的对象,只能是最后一个({@code numen.move.follow([entity])});</li>
 *   <li>{@link #optional}:选项,脚本里写在最后的选项表里 {@code {name = value}},一行命令里写成 {@code --name value};开关
 *       ({@link ArgType#bool()})是 {@code true}/{@code false},一行命令里写 {@code --name} 或 {@code --no-name}。</li>
 * </ul>
 * 名字就是选项表与 JSON 的键(小写、下划线,不能是脚本语言的保留字);一行命令里标志名写短横线({@code --block-ids}),读的时候
 * {@code _} 与 {@code -} 是同一个字符({@link #nameOf})。参数表的规矩(一次调用只有一类对象、没有必须写的选项……)在登记时查,见
 * {@link CommandGroup}。
 *
 * <h2>取值提示</h2>
 * 类型自己说得出的(整数的范围、开关)由 {@link ArgType} 说;类型说不出的由声明补上:
 * <ul>
 *   <li>{@link #values}:能写哪些值——固定的几个就列出来,不固定的写明去哪查(哪条命令、哪个事件给出它);</li>
 *   <li>{@link #whenOmitted}:可以不写的参数不写时会怎样,写成 "Omit to …" 的后半句。可以不写的参数都得有它:
 *       不写就有一个对的默认,登记时查。</li>
 * </ul>
 * 两条都接在说明后面({@link #explained}),帮助与 schema 读的是同一段文字。
 *
 * <h2>标志组</h2>
 * 成批出现、意思相关的标志(路线规格的十几个旋钮)可以归进一个组({@link #group}):动作的用法行只写一格
 * {@code [route flags]},不逐个列;完整清单在动作自己的帮助里,列在组名那一小节下。组只是帮助里怎么排,
 * 命令行上怎么写、schema 里怎么摊都不变。
 *
 * <pre>{@code
 * static final Param<String> TEXTURE = Param.optional("texture", ArgType.string(), "Which texture to wear.")
 *         .values("a texture id from the textures ysm options lists")
 *         .whenOmitted("use the model's first texture");
 * ...
 * String texture = args.get(TEXTURE);   // 没给是 null
 * }</pre>
 *
 * @param required    必须写:只有位置参数能是
 * @param positional  写在动作后面的位置参数;否则是标志
 * @param values      能写哪些值、去哪查;没写是 null
 * @param whenOmitted 可以不写的参数不写时会怎样("Omit to" 后面那半句);必须写的是 null
 * @param group       归进的标志组,用法行里整组写成一格 {@code [组名]};不归组是 null
 */
public record Param<T>(String name, ArgType<T> type, String description, boolean required, boolean positional,
                       String values, String whenOmitted, String group) {

    /** 参数名与 JSON 键同形:小写字母开头,小写字母、数字、下划线。 */
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    /** 标志组的名字:用法行里写成 {@code [组名]},帮助里是那一小节的标题。 */
    private static final Pattern GROUP = Pattern.compile("[a-z]+( [a-z]+)*");
    /** 关掉一个开关的前缀:{@code --no-sneak}。 */
    static final String NO = "no_";

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
        if (required && !positional) {
            throw new IllegalArgumentException("参数 " + name + " 必须写却是标志:必须写的做成位置参数");
        }
        if (!positional && type.span() == ArgType.Span.REST) {
            throw new IllegalArgumentException("参数 " + name + " 吃掉余下整行,不能当标志");
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
        if (group != null && (positional || !GROUP.matcher(group).matches())) {
            throw new IllegalArgumentException("参数 " + name + " 的标志组不合规:只有标志能归组,组名是小写英文词"
                    + "(如 \"route flags\"),得到 '" + group + "'");
        }
    }

    /** 必须写的位置参数:这条命令操作的东西。 */
    public static <T> Param<T> required(String name, ArgType<T> type, String description) {
        return new Param<>(name, type, description, true, true, null, null, null);
    }

    /** 可以不写的位置参数:只能是最后一个位置参数,不写时会怎样要写明({@link #whenOmitted})。 */
    public static <T> Param<T> optionalPositional(String name, ArgType<T> type, String description) {
        return new Param<>(name, type, description, false, true, null, null, null);
    }

    /** 标志 {@code --name value};开关只写 {@code --name}。不写时会怎样要写明({@link #whenOmitted})。 */
    public static <T> Param<T> optional(String name, ArgType<T> type, String description) {
        return new Param<>(name, type, description, false, false, null, null, null);
    }

    /** 能写哪些值、去哪查,例如 {@code "pot or stockpot"}、{@code "a model id as ysm options lists it"}。 */
    public Param<T> values(String values) {
        return new Param<>(name, type, description, required, positional, values, whenOmitted, group);
    }

    /** 可以不写的参数不写时会怎样,接在 "Omit to" 后面,例如 {@code "use the model's first texture"}。 */
    public Param<T> whenOmitted(String whenOmitted) {
        return new Param<>(name, type, description, required, positional, values, whenOmitted, group);
    }

    /** 归进一个标志组,例如 {@code "route flags"}:用法行里整组只写一格 {@code [route flags]}。只有标志能归组。 */
    public Param<T> group(String group) {
        return new Param<>(name, type, description, required, positional, values, whenOmitted, group);
    }

    /** 命令行上的标志名:参数名里的下划线写成短横线,{@code block_ids} 是 {@code block-ids}。 */
    String flag() {
        return name.replace('_', '-');
    }

    /**
     * 命令行上写的一个标志名(不带 {@code --})对应的参数名:{@code _} 与 {@code -} 是同一个字符。JSON 的键也照这条认。
     */
    static String nameOf(String written) {
        return written.replace('-', '_');
    }

    /** 一组参数的 JSON schema,字段按声明顺序。跑脚本的那个工具的 schema 经这里生成。 */
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

    /**
     * 用法里的样子:对象写名字,一串值或余下整段写 {@code name...},可以不写的包在 {@code [...]} 里;选项写 {@code name=…},开关写
     * {@code name=true}。
     */
    String usage() {
        if (!positional) {
            return type.isSwitch() ? name + "=true" : name + "=…";
        }
        String shown = type.span() == ArgType.Span.ONE ? name : name + "...";
        return required ? shown : "[" + shown + "]";
    }
}
