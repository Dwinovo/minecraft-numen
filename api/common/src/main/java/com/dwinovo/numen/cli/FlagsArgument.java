package com.dwinovo.numen.cli;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 可选参数的写法:位置参数之后的一串 {@code --name value},顺序随意。
 *
 * <h2>只有这一种机制</h2>
 * 标志的整段尾巴是 Brigadier 树上的<b>一个参数节点</b>,类型就是本类:它从第一个 {@code --} 读到行尾,
 * 逐个认出标志名,值交给那个参数自己的 {@link ArgType} 在同一个读头上读。所以标志和位置参数走同一次
 * Brigadier 解析,出错时报的位置是整行里的真实位置,用法照样附上;不另有一趟"先把标志拆出来"的预处理,
 * 也不靠 Brigadier 的 redirect 回环(那样每个标志一层子上下文,取值要一层层往回找)。
 *
 * <p>规矩:标志名就是参数名;每个标志都带一个值,{@code --name} 与值之间、两个标志之间各一个空格
 * (和 Brigadier 分隔位置参数的规矩一样);同一个标志不能写两次;没声明的标志拒掉并列出能写的。
 * 吃整行的类型不能当标志({@link Param} 登记时就拒),所以值总有边界。
 */
final class FlagsArgument implements ArgumentType<Map<String, Object>> {

    /** 标志尾巴那一格在树上的名字。参数名只能是小写字母开头(见 {@link Param}),撞不上它。 */
    static final String NODE = "--flags";
    /** 一个标志的打头:{@code --name}。一串值({@link ArgType#list})读到它就停。 */
    static final String PREFIX = "--";

    private static final DynamicCommandExceptionType EXPECTED_FLAG = new DynamicCommandExceptionType(
            usage -> new LiteralMessage("expected a flag (" + usage + ")"));
    private static final DynamicCommandExceptionType UNKNOWN_FLAG = new DynamicCommandExceptionType(
            what -> new LiteralMessage(String.valueOf(what)));
    private static final DynamicCommandExceptionType REPEATED_FLAG = new DynamicCommandExceptionType(
            name -> new LiteralMessage("--" + name + " is given twice"));
    private static final DynamicCommandExceptionType MISSING_VALUE = new DynamicCommandExceptionType(
            name -> new LiteralMessage("--" + name + " needs a value"));
    private static final DynamicCommandExceptionType EXPECTED_SPACE = new DynamicCommandExceptionType(
            name -> new LiteralMessage("expected a space after the value of --" + name));

    private final Map<String, Param<?>> flags = new LinkedHashMap<>();

    FlagsArgument(List<Param<?>> optional) {
        for (Param<?> p : optional) {
            flags.put(p.name(), p);
        }
    }

    @Override
    public Map<String, Object> parse(StringReader reader) throws CommandSyntaxException {
        Map<String, Object> out = new LinkedHashMap<>();
        while (reader.canRead()) {
            int start = reader.getCursor();
            if (!reader.getString().startsWith(PREFIX, reader.getCursor())) {
                throw EXPECTED_FLAG.createWithContext(reader, usage());
            }
            reader.setCursor(reader.getCursor() + PREFIX.length());
            String name = reader.readUnquotedString();
            Param<?> param = flags.get(name);
            if (param == null) {
                reader.setCursor(start);
                throw UNKNOWN_FLAG.createWithContext(reader, "unknown flag --" + name + "; flags here: " + usage());
            }
            if (out.containsKey(name)) {
                reader.setCursor(start);
                throw REPEATED_FLAG.createWithContext(reader, name);
            }
            if (!reader.canRead() || reader.peek() != ' ') {
                throw MISSING_VALUE.createWithContext(reader, name);
            }
            reader.skip();
            out.put(name, param.type().read(reader));
            if (reader.canRead()) {
                if (reader.peek() != ' ') {
                    throw EXPECTED_SPACE.createWithContext(reader, name);
                }
                reader.skip();
            }
        }
        return out;
    }

    /** 这一行写了的标志:写了,最后一个解析到的节点就是标志那一格;没写是空表。两侧的树都这样取。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> valuesIn(CommandContext<?> ctx) {
        List<? extends ParsedCommandNode<?>> nodes = ctx.getNodes();
        boolean written = !nodes.isEmpty() && nodes.get(nodes.size() - 1).getNode().getName().equals(NODE);
        return written ? ctx.getArgument(NODE, Map.class) : Map.of();
    }

    private String usage() {
        return flags.values().stream().map(Param::usage).collect(Collectors.joining(" "));
    }
}
