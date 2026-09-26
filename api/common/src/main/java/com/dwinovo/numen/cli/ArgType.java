package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.Schema;
import com.google.gson.JsonElement;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 一种命令参数的类型:命令行上怎么读、快捷工具的 JSON 怎么读、schema 里写成什么、帮助里怎么称呼。
 *
 * <h2>两个入口,一种读法</h2>
 * 命令行上的值由 Brigadier 的 {@link ArgumentType} 读;快捷工具收到的 JSON 值写成它在命令行上的样子,交给
 * <b>同一个</b> {@link ArgumentType} 读,而且必须整段读完。多数类型的样子就是字面文字;{@link #string()} 在命令行上
 * 靠引号装下空格,JSON 的字符串本身就有边界,所以它的值一律加上引号再读——否则带空格的名字命令行收、JSON 拒。一串值
 * ({@link #list})在 JSON 里是数组,每一项照同样的规矩读。所以同一个值从哪个入口进来,被接受还是被拒、报什么错都一样——
 * 转换只有这一处。这不是把整条命令拼回字符串再解析:每个值各自按自己的类型读,参数名来自 JSON 的键。
 *
 * <h2>现有的几种</h2>
 * 按用到的才开:整数(带给模型看的范围,或不设范围的方块坐标)、小数(带范围)、布尔、一个词(编号这类)、
 * 几个固定值之一、资源 id(配方、模型)、资源 id 或 {@code #标签}、一个值(模组给的名字,可能带空格或非英文,
 * 带空格时加引号)、余下整行(自由文字),以及把一种值组合成"余下整行里的一串"的 {@link #list}。要新的,就在这里加一种,
 * schema 与帮助跟着有。
 */
public final class ArgType<T> {

    private static final DynamicCommandExceptionType NOT_A_VALUE = new DynamicCommandExceptionType(
            hint -> new LiteralMessage("expected " + hint));
    private static final DynamicCommandExceptionType TRAILING = new DynamicCommandExceptionType(
            hint -> new LiteralMessage("expected a single " + hint));
    private static final SimpleCommandExceptionType NO_ID = new SimpleCommandExceptionType(
            new LiteralMessage("expected an id like minecraft:oak_log"));
    private static final DynamicCommandExceptionType BAD_ID = new DynamicCommandExceptionType(
            id -> new LiteralMessage("'" + id + "' is not a valid id"));
    private static final SimpleCommandExceptionType NO_STRING = new SimpleCommandExceptionType(
            new LiteralMessage("expected a string"));
    private static final DynamicCommandExceptionType NOT_A_CHOICE = new DynamicCommandExceptionType(
            choices -> new LiteralMessage("expected one of " + choices));

    /** 往 schema 里写这一个字段:{@link Schema.Builder} 是工具 schema 的唯一写法,这里只挑用哪个方法。 */
    @FunctionalInterface
    private interface SchemaField {
        void add(Schema.Builder schema, String name, String description, boolean required);
    }

    /** 快捷工具的一个 JSON 值读成值。 */
    @FunctionalInterface
    private interface FromJson<T> {
        T read(JsonElement value) throws CommandSyntaxException;
    }

    private final ArgumentType<T> brigadier;
    private final String kind;
    private final String hint;
    private final boolean restOfLine;
    private final SchemaField schema;
    private final FromJson<T> json;

    /** JSON 值是一个字面值,文字原样就是它在命令行上的样子。 */
    private ArgType(ArgumentType<T> brigadier, String kind, String hint, boolean restOfLine, SchemaField schema) {
        this(brigadier, kind, hint, restOfLine, schema, literal(brigadier, hint, UnaryOperator.identity()));
    }

    private ArgType(ArgumentType<T> brigadier, String kind, String hint, boolean restOfLine, SchemaField schema,
                    FromJson<T> json) {
        this.brigadier = brigadier;
        this.kind = kind;
        this.hint = hint;
        this.restOfLine = restOfLine;
        this.schema = schema;
        this.json = json;
    }

    /**
     * 一个 JSON 值是一个字面值:写成它在命令行上的样子,用同一个读法整段读完。
     *
     * @param written 一个 JSON 值的文字在命令行上写成什么样
     */
    private static <T> FromJson<T> literal(ArgumentType<T> brigadier, String hint, UnaryOperator<String> written) {
        return value -> {
            if (value == null || !value.isJsonPrimitive()) {
                throw NOT_A_VALUE.create(hint);
            }
            StringReader reader = new StringReader(written.apply(value.getAsString()));
            T parsed = brigadier.parse(reader);
            if (reader.canRead()) {
                throw TRAILING.createWithContext(reader, hint);
            }
            return parsed;
        };
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

    /** 不设范围的整数:方块坐标这类,哪个值都合法,范围没什么可告诉模型的。 */
    public static ArgType<Integer> integer() {
        return new ArgType<>(IntegerArgumentType.integer(), "integer", "integer", false,
                (s, name, desc, required) -> {
                    if (required) s.integer(name, desc);
                    else s.optionalInteger(name, desc);
                });
    }

    /** 布尔:{@code true} 或 {@code false}。当标志时也要写值({@code --have_only true})。 */
    public static ArgType<Boolean> bool() {
        return new ArgType<>(BoolArgumentType.bool(), "boolean", "true or false", false,
                (s, name, desc, required) -> {
                    if (required) s.bool(name, desc);
                    else s.optionalBool(name, desc);
                });
    }

    /** 一个词:字母、数字与 {@code _-.+},不带空格。编号(t42、tm3)这类。 */
    public static ArgType<String> word() {
        return new ArgType<>(StringArgumentType.word(), "word", "word", false, ArgType::stringField);
    }

    /**
     * 资源 id:配方、物品、模组模型这类 {@code 命名空间:路径}。字符集与合法性都用原版 {@link ResourceLocation}
     * 自己的规则({@code a-z0-9_.-} 加路径里的 {@code /}),不另写一份;不写命名空间就是 {@code minecraft:},和原版指令一样。
     */
    public static ArgType<ResourceLocation> id() {
        return new ArgType<>(ArgType::readId, "id", "id, e.g. minecraft:oak_log", false, ArgType::stringField);
    }

    /**
     * 一个值:到下一个空格为止的任意字符(可以是中文、带 {@code /} 与大写);值里有空格就用引号括起来,
     * 引号内的写法照 Brigadier 的带引号字符串(反斜杠转义)。模组自己起的名字(YSM 的模型文件名、女仆模型包的
     * 角色名)用它——这些名字的字符集不归我们定。
     */
    public static ArgType<String> string() {
        ArgumentType<String> read = ArgType::readString;
        String hint = "string, quote it if it has spaces";
        return new ArgType<>(read, "string", hint, false, ArgType::stringField, literal(read, hint, ArgType::quoted));
    }

    /** 加上双引号,里面的反斜杠与双引号转义——{@link StringReader#readQuotedString} 读回来就是原文。 */
    private static String quoted(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static ResourceLocation readId(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && ResourceLocation.isAllowedInResourceLocation(reader.peek())) {
            reader.skip();
        }
        String raw = reader.getString().substring(start, reader.getCursor());
        if (raw.isEmpty()) {
            throw NO_ID.createWithContext(reader);
        }
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) {
            reader.setCursor(start);
            throw BAD_ID.createWithContext(reader, raw);
        }
        return id;
    }

    private static String readString(StringReader reader) throws CommandSyntaxException {
        if (reader.canRead() && StringReader.isQuotedStringStart(reader.peek())) {
            return reader.readQuotedString();
        }
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        if (reader.getCursor() == start) {
            throw NO_STRING.createWithContext(reader);
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    /**
     * 余下的整行,原样收下(可以带空格,不必加引号)。它吃掉后面的一切,所以只能是动作的最后一个必填参数,
     * 也不能当可选标志——这两条在 {@link Param} 与 {@link CommandGroup} 里登记时就查。
     */
    public static ArgType<String> text() {
        return new ArgType<>(StringArgumentType.greedyString(), "text", "text, the rest of the line", true,
                ArgType::stringField);
    }

    /**
     * 小数。和 {@link #integer(int, int)} 一样,{@code min..max} 是告诉模型的约定,读的时候不拦越界的值:夹住还是拒绝
     * 是动作自己的语义。
     */
    public static ArgType<Double> number(double min, double max) {
        return new ArgType<>(DoubleArgumentType.doubleArg(), "number", "number " + plain(min) + "-" + plain(max), false,
                (s, name, desc, required) -> {
                    if (required) s.number(name, desc, min, max);
                    else s.optionalNumber(name, desc, min, max);
                });
    }

    /** 帮助里的数不带多余的 {@code .0}:{@code 1-64} 而不是 {@code 1.0-64.0}。 */
    private static String plain(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /**
     * 几个固定值之一(小写英文,不带空格),比如实体的种类 {@code hostile}、{@code passive}。写了别的就当场拒,
     * 报错列出能写的几个;schema 里是一个 {@code enum}。
     */
    public static ArgType<String> oneOf(String... choices) {
        List<String> allowed = List.of(choices);
        String listed = String.join(", ", allowed);
        return new ArgType<>(reader -> {
            int start = reader.getCursor();
            String value = reader.readUnquotedString();
            if (!allowed.contains(value)) {
                reader.setCursor(start);
                throw NOT_A_CHOICE.createWithContext(reader, listed);
            }
            return value;
        }, String.join("|", allowed), "one of " + listed, false,
                (s, name, desc, required) -> {
                    if (required) s.enumStr(name, desc, choices);
                    else s.optionalEnum(name, desc, choices);
                });
    }

    /**
     * 资源 id,或 {@code #} 开头的标签:"这一种"或"这一类"({@code minecraft:fortress} / {@code #minecraft:village},
     * {@code iron_ore} / {@code #minecraft:logs})。标签是原版自己的写法(数据包里引用标签就这么写)。id 的字符集与合法性
     * 同 {@link #id()};读出来的是写下的原文(带不带 {@code #}、写没写命名空间都照原样),在哪个注册表里认、认不出来
     * 怎么说,是用它的动作的事——同一个 {@code #minecraft:village} 在结构表里是一类、在群系表里查无此类。
     */
    public static ArgType<String> idOrTag() {
        return new ArgType<>(ArgType::readIdOrTag, "id", "id or #tag, e.g. minecraft:oak_log or #minecraft:logs",
                false, ArgType::stringField);
    }

    private static String readIdOrTag(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        if (reader.canRead() && reader.peek() == '#') {
            reader.skip();
        }
        int idStart = reader.getCursor();
        while (reader.canRead() && ResourceLocation.isAllowedInResourceLocation(reader.peek())) {
            reader.skip();
        }
        String id = reader.getString().substring(idStart, reader.getCursor());
        if (id.isEmpty()) {
            reader.setCursor(start);
            throw NO_ID.createWithContext(reader);
        }
        if (ResourceLocation.tryParse(id) == null) {
            reader.setCursor(start);
            throw BAD_ID.createWithContext(reader, reader.getString().substring(start, idStart) + id);
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    /**
     * 一串同一种的值:命令行上是余下整行里一个空格隔开的一个个值({@code iron_ore deepslate_iron_ore}),每个都按
     * {@code element} 的读法读;快捷工具里是一个 JSON 数组,每一项按 {@code element} 读 JSON 值的规矩读。至少一个。
     * 它吃掉余下整行,和 {@link #text()} 一样只能是动作的最后一个必填参数。
     */
    public static ArgType<List<String>> list(ArgType<String> element) {
        if (element.restOfLine) {
            throw new IllegalArgumentException("一串值里的每一个不能自己吃掉余下整行");
        }
        String hint = element.hint + "; one or more, separated by spaces";
        return new ArgType<List<String>>(reader -> {
            List<String> values = new ArrayList<>();
            values.add(element.read(reader));
            while (reader.canRead()) {
                if (reader.peek() != ' ') {
                    throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherExpectedArgumentSeparator()
                            .createWithContext(reader);
                }
                reader.skip();
                values.add(element.read(reader));
            }
            return List.copyOf(values);
        }, element.kind + "...", hint, true,
                (s, name, desc, required) -> {
                    if (required) s.stringArray(name, desc, 1);
                    else s.optionalStringArray(name, desc);
                },
                value -> {
                    if (value == null || !value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
                        throw NOT_A_VALUE.create("a list: " + hint);
                    }
                    List<String> values = new ArrayList<>();
                    for (JsonElement item : value.getAsJsonArray()) {
                        values.add(element.fromJson(item));
                    }
                    return List.copyOf(values);
                });
    }

    private static void stringField(Schema.Builder s, String name, String desc, boolean required) {
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

    /** 快捷工具的 JSON 值:字面值写成它在命令行上的样子,用同一个读法整段读完;一串值({@link #list})逐个这样读。 */
    T fromJson(JsonElement value) throws CommandSyntaxException {
        return json.read(value);
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
