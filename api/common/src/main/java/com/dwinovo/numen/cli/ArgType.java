package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.Schema;
import com.google.gson.JsonElement;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;

/**
 * 一种命令参数的类型:命令行上怎么读、快捷工具的 JSON 怎么读、schema 里写成什么、帮助里怎么称呼。
 *
 * <h2>两个入口,一种读法</h2>
 * 命令行上的值由 Brigadier 的 {@link ArgumentType} 读;快捷工具收到的 JSON 值取它的字面文字,交给<b>同一个</b>
 * {@link ArgumentType} 读,而且必须整段读完。所以同一个值从哪个入口进来,被接受还是被拒、报什么错都一样——
 * 转换只有这一处。这不是把整条命令拼回字符串再解析:每个值各自按自己的类型读,参数名来自 JSON 的键。
 *
 * <h2>现有的几种</h2>
 * 按用到的才开:整数(带给模型看的范围)、一个词(编号这类)、余下整行(自由文字)。以后要物品 id、方块坐标,
 * 就在这里加一种,schema 与帮助跟着有。
 */
public final class ArgType<T> {

    private static final DynamicCommandExceptionType NOT_A_VALUE = new DynamicCommandExceptionType(
            hint -> new LiteralMessage("expected " + hint));
    private static final DynamicCommandExceptionType TRAILING = new DynamicCommandExceptionType(
            hint -> new LiteralMessage("expected a single " + hint));

    /** 往 schema 里写这一个字段:{@link Schema.Builder} 是工具 schema 的唯一写法,这里只挑用哪个方法。 */
    @FunctionalInterface
    private interface SchemaField {
        void add(Schema.Builder schema, String name, String description, boolean required);
    }

    private final ArgumentType<T> brigadier;
    private final String kind;
    private final String hint;
    private final boolean restOfLine;
    private final SchemaField schema;

    private ArgType(ArgumentType<T> brigadier, String kind, String hint, boolean restOfLine, SchemaField schema) {
        this.brigadier = brigadier;
        this.kind = kind;
        this.hint = hint;
        this.restOfLine = restOfLine;
        this.schema = schema;
    }

    /**
     * 整数。{@code min..max} 写进 schema 与帮助,是告诉模型的约定;读的时候不拦越界的值,原样交给处理函数。
     * 越界了是夹住还是拒绝、回执里怎么说,是那个动作自己的语义({@code task timer} 夹住并在回执里说明你要的
     * 和实际定的)——若在这里按 Brigadier 的范围拒掉,处理函数就没机会把话说清楚。
     */
    public static ArgType<Integer> integer(int min, int max) {
        return new ArgType<>(IntegerArgumentType.integer(), "integer", "integer " + min + "-" + max, false,
                (s, name, desc, required) -> {
                    if (required) s.integer(name, desc, min, max);
                    else s.optionalInteger(name, desc, min, max);
                });
    }

    /** 一个词:字母、数字与 {@code _-.+},不带空格。编号(t42、tm3)这类。 */
    public static ArgType<String> word() {
        return new ArgType<>(StringArgumentType.word(), "word", "word", false, ArgType::string);
    }

    /**
     * 余下的整行,原样收下(可以带空格,不必加引号)。它吃掉后面的一切,所以只能是动作的最后一个必填参数,
     * 也不能当可选标志——这两条在 {@link Param} 与 {@link CommandGroup} 里登记时就查。
     */
    public static ArgType<String> text() {
        return new ArgType<>(StringArgumentType.greedyString(), "text", "text, the rest of the line", true,
                ArgType::string);
    }

    private static void string(Schema.Builder s, String name, String desc, boolean required) {
        if (required) s.string(name, desc);
        else s.optionalString(name, desc);
    }

    /** 命令行上的读法。 */
    ArgumentType<T> brigadier() {
        return brigadier;
    }

    /** 从命令行当前位置读一个值(标志的值也经这里)。 */
    T read(StringReader reader) throws CommandSyntaxException {
        return brigadier.parse(reader);
    }

    /** 快捷工具的 JSON 值:取它的字面文字,用同一个读法整段读完。 */
    T fromJson(JsonElement value) throws CommandSyntaxException {
        if (value == null || !value.isJsonPrimitive()) {
            throw NOT_A_VALUE.create(hint);
        }
        StringReader reader = new StringReader(value.getAsString());
        T parsed = read(reader);
        if (reader.canRead()) {
            throw TRAILING.createWithContext(reader, hint);
        }
        return parsed;
    }

    /** 类型的名字,比如 {@code integer};标志的用法里写它。 */
    String kind() {
        return kind;
    }

    /** 帮助里的完整称呼,比如 {@code integer 1-1200}。 */
    String hint() {
        return hint;
    }

    /** 是否吃掉余下整行。 */
    boolean restOfLine() {
        return restOfLine;
    }

    void addTo(Schema.Builder builder, String name, String description, boolean required) {
        schema.add(builder, name, description, required);
    }
}
