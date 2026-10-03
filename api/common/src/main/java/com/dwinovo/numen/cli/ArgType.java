package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.JsonValues;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.area.AreaRef;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * 一种命令参数的类型:命令行上怎么读、快捷工具的 JSON 怎么读、schema 里写成什么、帮助里怎么称呼。
 *
 * <h2>两个入口</h2>
 * 人写的一行命令由 Brigadier 的 {@link ArgumentType} 读(坐标是三个数 {@code 120 64 -35});脚本的值换成 JSON 交给
 * {@link #fromJson}。多数类型的 JSON 值是一个字面值,写成它在命令行上的样子交给<b>同一个</b> {@link ArgumentType} 整段读完;
 * {@link #string()} 在命令行上靠引号装下空格,JSON 的字符串本身就有边界,所以它的值一律加上引号再读。位置、实体这几样在脚本里是表
 * ({@link Shapes}),JSON 这一侧另有读法:一格只认带键的表 {@code {x = …, y = …, z = …}} 或带 {@code pos} 的表,一只实体只认编号或带
 * {@code id} 的表——一种值一种写法。读成什么、报什么错都在这一处。
 *
 * <h2>对象的写法只在这里读</h2>
 * 命令操作的对象全仓一种写法,命令只声明它的对象是哪一类:
 * <ul>
 *   <li>一格({@link #cell()}):脚本里是 Pos {@code {x = 120, y = 64, z = -35}},或任何带 {@code pos} 的表(方块、实体、掉落物);
 *       小数按 Minecraft 的定义换成所在的那一格。一行命令里是三个整数 {@code 120 64 -35},也收 {@code 120,64,-35};</li>
 *   <li>一处({@link #place()}):一格、一列 {@code {x = …, z = …}}、一个高度 {@code {y = …}},或主人名下的一块区域;</li>
 *   <li>区域({@link #area()}):{@code ores} 指整块,{@code ores/g3} 指一部分,规矩在 {@link AreaRef#parse};</li>
 *   <li>实体({@link #entity()}):{@code numen.scan.entities} 列出的运行期编号,或那只实体的表(带 {@code id});</li>
 *   <li>方块与物品({@link #id()}):资源 id,不写命名空间就是 {@code minecraft:};标签({@link #idOrTag()}):{@code #minecraft:logs}。</li>
 * </ul>
 *
 * <h2>一个值占多宽</h2>
 * 多数类型是一个值({@link Span#ONE},一格坐标也是一个值,只是占三个词);一串值({@link #list})是空格隔开的几个,读到行尾或下一个
 * 标志为止({@link Span#SEVERAL});余下整行({@link #text})吃掉后面的一切({@link Span#REST})。宽度决定它能放在参数表的哪儿,
 * 这条规矩在 {@link Param} 与 {@link CommandGroup} 登记时查。
 *
 * <h2>开关</h2>
 * {@link #bool()} 只做标志,而且是开关:写 {@code --sneak} 就是开,{@code --no-sneak} 是关,不写值(见 {@link FlagsArgument})。
 * 快捷工具的 JSON 里照样是 {@code true}/{@code false}。
 *
 * <h2>读出来的是什么</h2>
 * 一个值的写法读通了,内容还可以交给用它的一方去认:{@link #as} 在一种写法上接一个解读(方块状态、图例的一项),认不了就是
 * 这个值写错了,报错指在它的开头。解读只在这里挂一次,命令行、快捷工具、只读不执行的那一棵树读到的都是认过的值。
 *
 * <h2>对象的类别</h2>
 * 每种类型有一个类别({@link #noun()}):登记时查"一条命令的位置参数只有一类对象"就比它。一串值的类别是它一项的类别。
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
    private static final SimpleCommandExceptionType NO_CELL = new SimpleCommandExceptionType(
            new LiteralMessage("expected a cell: three whole numbers x y z"));
    /** 一格后面接着 {@code ..}(多半是想写一个盒子):一格只是一格,一片格子是区域。 */
    private static final SimpleCommandExceptionType NOT_ONE_CELL = new SimpleCommandExceptionType(
            new LiteralMessage("a cell is one x y z; a box or any other stretch of cells is an area — frame it as one "
                    + "(numen.area.add(name, {box = {from, to}})) and name the area"));
    private static final SimpleCommandExceptionType NO_PLACE = new SimpleCommandExceptionType(
            new LiteralMessage("expected a place: x y z (a cell), x z (a column), y (a height), or an area "
                    + "name like ores or ores/g3"));
    /** 读成了写法,内容却不成立(方块名认不出、区域名不合规矩……):说法由认它的那一方给。 */
    private static final DynamicCommandExceptionType REJECTED = new DynamicCommandExceptionType(
            why -> new LiteralMessage(String.valueOf(why)));
    private static final SimpleCommandExceptionType NO_ENTITY = new SimpleCommandExceptionType(
            new LiteralMessage("expected an entity id as numen.scan.entities lists it, like 184"));
    /** 脚本里一格的写法(JSON 这一侧):带键的表。 */
    static final String POS_SHAPE = "a Pos {x = …, y = …, z = …} or anything with a pos (a Block, an Entity, an Item)";
    /** UUID 的规范写法:8-4-4-4-12 位十六进制。 */
    private static final java.util.regex.Pattern UUID_TEXT = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** 标签的记号:原版标签文件里引用别的标签就这么写,{@code #minecraft:logs}。 */
    private static final char TAG = '#';
    /** 一格坐标写成一个词时三个数之间的分隔。 */
    private static final char COMMA = ',';
    /** 两格之间写 {@code ..} 是想写一段范围:报"一格只是一格"。 */
    private static final String RANGE = "..";
    /** 坐标里一个数至多几位:再长就超出整数了。 */
    private static final int MAX_DIGITS = 9;

    /** 一个值在命令行上占多宽:一个值、空格隔开的几个(到行尾或下一个标志为止)、余下整行。 */
    enum Span { ONE, SEVERAL, REST }

    /** 一串值({@link #list})的一项在 JSON 数组里是什么;不能做一串值里一项的类型是 NONE。 */
    private enum Item { STRING, INTEGER, NONE }

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
    private final String noun;
    private final String hint;
    private final Span span;
    private final Item item;
    /** 一个值在命令行上占几个词(坐标):一串这样的值从 JSON 读时,数组各项接成一行再读。 */
    private final boolean words;
    private final boolean flagSwitch;
    private final SchemaField schema;
    private final FromJson<T> json;
    private final Function<T, String> written;
    /** 读好的值的文字本身,不带一行命令上为装下空格加的引号:写进脚本的样子({@link #plain})从它来。 */
    private Function<T, String> raw;
    /** 一串值的一项占几个词(一串格子):脚本把一张全是数的表当成一项。不是一串值是 false。 */
    private boolean itemWords;
    /** 一串值的一项的类型;不是一串值是 null。 */
    private ArgType<?> element;
    /** 脚本里它的类型:签名里写成什么({@link ScriptType})。 */
    private ScriptType script;
    /** 读好的值写成脚本里的值(位置是 Pos 的表、实体是编号);null 是按文字写({@link #plain})。 */
    private Function<T, Object> toScript;

    /**
     * 一个值;JSON 值是一个字面值,文字原样就是它在命令行上的样子,读好的值写回去也就是它的文字
     * ({@link String#valueOf}:整数、小数、词、id、固定值之一都是这样)。
     */
    private ArgType(ArgumentType<T> brigadier, String kind, String noun, String hint, Item item, SchemaField schema) {
        this(brigadier, kind, noun, hint, Span.ONE, item, false, false, schema,
                literal(brigadier, hint, UnaryOperator.identity()), String::valueOf);
    }

    /** @param written 读好的值写回命令行上是什么样子,再读一遍得到的是同一个值 */
    private ArgType(ArgumentType<T> brigadier, String kind, String noun, String hint, Span span, Item item,
                    boolean words, boolean flagSwitch, SchemaField schema, FromJson<T> json,
                    Function<T, String> written) {
        this.brigadier = brigadier;
        this.kind = kind;
        this.noun = noun;
        this.hint = hint;
        this.span = span;
        this.item = item;
        this.words = words;
        this.flagSwitch = flagSwitch;
        this.schema = schema;
        this.json = json;
        this.written = written;
        this.raw = written;
        this.script = item == Item.INTEGER ? ScriptType.INTEGER : ScriptType.STRING;
    }

    /** 定下脚本里的类型与写法(位置、实体这些在脚本里是表的)。 */
    private ArgType<T> scripted(ScriptType type, Function<T, Object> toScript) {
        this.script = type;
        this.toScript = toScript;
        return this;
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
            return whole(brigadier, written.apply(value.getAsString()), hint);
        };
    }

    /** 一段文字整段按 {@code brigadier} 读完;读不完是写错了。 */
    private static <T> T whole(ArgumentType<T> brigadier, String text, String hint) throws CommandSyntaxException {
        StringReader reader = new StringReader(text);
        T parsed = brigadier.parse(reader);
        if (reader.canRead()) {
            throw TRAILING.createWithContext(reader, hint);
        }
        return parsed;
    }

    /**
     * 脚本给的值形状不对,而且看得出她想写的是什么(三个数的列表、{@code "x y z"} 的字符串想写一格):错在哪,加上照这一种写法该写成
     * 的那个值。读参数的一处({@link CommandArgs#fromJson})把它写进错误值的 {@code hint}。
     */
    static final class WrongShape extends RuntimeException {

        final transient Object instead;

        WrongShape(String message, Object instead) {
            super(message, null, false, false);
            this.instead = instead;
        }
    }

    /** 脚本给的一个 JSON 值写回脚本里的样子,报错里说"你给了什么"。 */
    static String given(JsonElement value) {
        return ScriptEngine.IN_USE.value(JsonValues.toJava(value));
    }

    /** 一格的 JSON:带键的表(小数换成所在的一格),或带 {@code pos} 的表。 */
    private static BlockPos cellFromJson(JsonElement value) throws CommandSyntaxException {
        if (value != null && value.isJsonObject()) {
            JsonObject o = value.getAsJsonObject();
            if (o.get("pos") instanceof JsonObject pos) {
                return cellFromJson(pos);
            }
            Double x = number(o, "x");
            Double y = number(o, "y");
            Double z = number(o, "z");
            if (x != null && y != null && z != null) {
                return BlockPos.containing(x, y, z);
            }
            throw REJECTED.create("expected " + POS_SHAPE + "; got " + given(value)
                    + (x != null && z != null ? ", which has no y" : ""));
        }
        Object instead = coordinates(value, 3);
        if (instead instanceof java.util.Map<?, ?> pos && pos.size() == 3) {
            throw new WrongShape("a cell is a Pos with named fields; got " + given(value), instead);
        }
        throw REJECTED.create("expected " + POS_SHAPE + "; got " + given(value));
    }

    /** 一个数字段;没有或不是数是 null。 */
    private static Double number(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : null;
    }

    /**
     * 想写坐标却写成了别的样子(数的列表、{@code "120 64 -35"} 这样的字符串、一个数):一到 {@code max} 个数时,照带键的写法该写成的那张表
     * ({@code {x, y, z}}、{@code {x, z}}、{@code {y}});看不出是坐标是 null。
     */
    private static Object coordinates(JsonElement value, int max) {
        List<Long> numbers = new ArrayList<>();
        if (value != null && value.isJsonArray()) {
            for (JsonElement e : value.getAsJsonArray()) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                    return null;
                }
                numbers.add((long) Math.floor(e.getAsDouble()));
            }
        } else if (value != null && value.isJsonPrimitive()) {
            String text = value.getAsString().strip();
            if (!text.matches("-?\\d+(\\.\\d+)?([ ,]+-?\\d+(\\.\\d+)?)*")) {
                return null;
            }
            for (String part : text.split("[ ,]+")) {
                numbers.add((long) Math.floor(Double.parseDouble(part)));
            }
        }
        if (numbers.isEmpty() || numbers.size() > max) {
            return null;
        }
        java.util.Map<String, Object> pos = new java.util.LinkedHashMap<>();
        switch (numbers.size()) {
            case 3 -> {
                pos.put("x", numbers.get(0));
                pos.put("y", numbers.get(1));
                pos.put("z", numbers.get(2));
            }
            case 2 -> {
                pos.put("x", numbers.get(0));
                pos.put("z", numbers.get(1));
            }
            default -> pos.put("y", numbers.get(0));
        }
        return pos;
    }

    /** 一处的 JSON:区域名;带键的表是一格({@code x y z})、一列({@code x z})或一个高度({@code y});带 {@code pos} 的表是那一格。 */
    private static Place placeFromJson(JsonElement value) throws CommandSyntaxException {
        if (value != null && value.isJsonObject()) {
            JsonObject o = value.getAsJsonObject();
            if (o.get("pos") instanceof JsonObject) {
                return Place.cell(cellFromJson(value));
            }
            Double x = number(o, "x");
            Double y = number(o, "y");
            Double z = number(o, "z");
            if (x != null && z != null) {
                return y != null ? Place.cell(BlockPos.containing(x, y, z))
                        : new Place((int) Math.floor(x), null, (int) Math.floor(z), null);
            }
            if (x == null && z == null && y != null) {
                return new Place(null, (int) Math.floor(y), null, null);
            }
            throw REJECTED.create("expected a place: " + POS_SHAPE + ", a column {x = …, z = …}, a height {y = …}, or "
                    + "an area name like \"ores\"; got " + given(value));
        }
        Object instead = coordinates(value, 3);
        if (instead != null) {
            throw new WrongShape("a place given by coordinates is a table with named fields; got " + given(value),
                    instead);
        }
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String name = value.getAsString();
            StringReader reader = new StringReader(name);
            Place place = Place.area(readAreaRef(reader, 0));
            if (reader.canRead()) {
                throw TRAILING.createWithContext(reader, "area name");
            }
            return place;
        }
        throw REJECTED.create("expected a place: " + POS_SHAPE + ", a column {x = …, z = …}, a height {y = …}, or an "
                + "area name like \"ores\"; got " + given(value));
    }

    /** 一只实体的 JSON:编号,或带 {@code id} 的那张表。 */
    private static EntityRef entityFromJson(JsonElement value) throws CommandSyntaxException {
        JsonElement id = value != null && value.isJsonObject() ? value.getAsJsonObject().get("id") : value;
        if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber()
                && id.getAsDouble() == Math.rint(id.getAsDouble())) {
            return EntityRef.id(id.getAsInt());
        }
        if (id != null && id.isJsonPrimitive() && id.getAsString().matches("\\d{1," + MAX_DIGITS + "}")) {
            throw new WrongShape("an entity id is a number; got " + given(value), Long.parseLong(id.getAsString()));
        }
        throw REJECTED.create("expected an entity: its id as numen.scan.entities lists it (184), or the Entity itself; got "
                + given(value));
    }

    /**
     * 整数。{@code min..max} 写进 schema 与帮助,是告诉模型的约定;读的时候不拦越界的值,原样交给处理函数。
     * 越界了是夹住还是拒绝、回执里怎么说,是那个动作自己的语义({@code task timer} 夹住并在回执里说明你要的
     * 和实际定的)——若在这里按 Brigadier 的范围拒掉,处理函数就没机会把话说清楚。
     */
    public static ArgType<Integer> integer(int min, int max) {
        return new ArgType<>(IntegerArgumentType.integer(), "integer", "integer", "integer " + min + "-" + max,
                Item.INTEGER, (s, name, desc, required) -> {
                    if (required) s.integer(name, desc, min, max);
                    else s.optionalInteger(name, desc, min, max);
                });
    }

    /** 不设范围的整数:哪个值都合法,范围没什么可告诉模型的。 */
    public static ArgType<Integer> integer() {
        return new ArgType<>(IntegerArgumentType.integer(), "integer", "integer", "integer", Item.INTEGER,
                (s, name, desc, required) -> {
                    if (required) s.integer(name, desc);
                    else s.optionalInteger(name, desc);
                });
    }

    /**
     * 开关:只做标志,写 {@code --name} 是开、{@code --no-name} 是关,不跟值(见 {@link FlagsArgument})。快捷工具里是 JSON 的
     * {@code true}/{@code false}。当位置参数在登记时就拒({@link CommandGroup})。
     */
    public static ArgType<Boolean> bool() {
        BoolArgumentType read = BoolArgumentType.bool();
        String hint = "switch: true or false";
        return new ArgType<>(read, "switch", "switch", hint, Span.ONE, Item.NONE, false, true,
                (s, name, desc, required) -> {
                    if (required) s.bool(name, desc);
                    else s.optionalBool(name, desc);
                }, literal(read, hint, UnaryOperator.identity()), String::valueOf)
                .scripted(ScriptType.BOOLEAN, null);
    }

    /** 一个词:字母、数字与 {@code _-.+},不带空格。编号(t42、tm3)这类。 */
    public static ArgType<String> word() {
        return new ArgType<>(StringArgumentType.word(), "word", "word", "word", Item.STRING, ArgType::stringField);
    }

    /**
     * 资源 id:配方、物品、模组模型这类 {@code 命名空间:路径}。字符集与合法性都用原版 {@link ResourceLocation}
     * 自己的规则({@code a-z0-9_.-} 加路径里的 {@code /}),不另写一份;不写命名空间就是 {@code minecraft:},和原版指令一样。
     */
    public static ArgType<ResourceLocation> id() {
        return new ArgType<>(ArgType::readId, "id", "id", "id, e.g. minecraft:oak_log (minecraft: may be left out)",
                Item.STRING, ArgType::stringField);
    }

    /**
     * 一个值:到下一个空格为止的任意字符(可以是中文、带 {@code /} 与大写);值里有空格就用引号括起来,
     * 引号内的写法照 Brigadier 的带引号字符串(反斜杠转义)。模组自己起的名字(YSM 的模型文件名、女仆模型包的
     * 角色名)用它——这些名字的字符集不归我们定。
     */
    public static ArgType<String> string() {
        ArgumentType<String> read = ArgType::readString;
        String hint = "string";
        ArgType<String> string = new ArgType<>(read, "string", "string", hint, Span.ONE, Item.STRING, false, false,
                ArgType::stringField, literal(read, hint, ArgType::quoted), ArgType::quotedIfNeeded);
        string.raw = UnaryOperator.identity();
        return string;
    }

    /**
     * 写回命令行:不加引号读得回原样的就不加——没有空格、不以引号打头(否则读成带引号的串)、不以 {@code --} 打头
     * (否则一串值读到它就当成下一个标志);否则加上引号。
     */
    private static String quotedIfNeeded(String text) {
        boolean bare = !text.isEmpty() && text.indexOf(' ') < 0 && !StringReader.isQuotedStringStart(text.charAt(0))
                && !text.startsWith(FlagsArgument.PREFIX);
        return bare ? text : quoted(text);
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
     * 余下的整行,原样收下(可以带空格,不必加引号)。它吃掉后面的一切,所以只能是动作的最后一个位置参数,
     * 也不能当可选标志——这两条在 {@link Param} 与 {@link CommandGroup} 里登记时就查。
     */
    public static ArgType<String> text() {
        StringArgumentType read = StringArgumentType.greedyString();
        String hint = "text";
        return new ArgType<>(read, "text", "text", hint, Span.REST, Item.NONE, false, false, ArgType::stringField,
                literal(read, hint, UnaryOperator.identity()), UnaryOperator.identity());
    }

    /**
     * 小数。和 {@link #integer(int, int)} 一样,{@code min..max} 是告诉模型的约定,读的时候不拦越界的值:夹住还是拒绝
     * 是动作自己的语义。
     */
    public static ArgType<Double> number(double min, double max) {
        return new ArgType<>(DoubleArgumentType.doubleArg(), "number", "number",
                "number " + plain(min) + "-" + plain(max), Item.NONE,
                (s, name, desc, required) -> {
                    if (required) s.number(name, desc, min, max);
                    else s.optionalNumber(name, desc, min, max);
                }).scripted(ScriptType.NUMBER, null);
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
        }, String.join("|", allowed), "choice", "one of " + listed, Item.STRING,
                (s, name, desc, required) -> {
                    if (required) s.enumStr(name, desc, choices);
                    else s.optionalEnum(name, desc, choices);
                }).scripted(ScriptType.choice(allowed), null);
    }

    /**
     * 资源 id,或 {@code #} 开头的标签:"这一种"或"这一类"({@code minecraft:fortress} / {@code #minecraft:village},
     * {@code iron_ore} / {@code #minecraft:logs})。标签是原版自己的写法(数据包里引用标签就这么写)。id 的字符集与合法性
     * 同 {@link #id()};读出来的是写下的原文(带不带 {@code #}、写没写命名空间都照原样),在哪个注册表里认、认不出来
     * 怎么说,是用它的动作的事——同一个 {@code #minecraft:village} 在结构表里是一类、在群系表里查无此类。
     */
    public static ArgType<String> idOrTag() {
        return new ArgType<>(ArgType::readIdOrTag, "id", "id", "id or #tag, e.g. minecraft:oak_log or #minecraft:logs",
                Item.STRING, ArgType::stringField);
    }

    private static String readIdOrTag(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        if (reader.canRead() && reader.peek() == TAG) {
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
     * 一格坐标:三个整数,空格隔开({@code 120 64 -35})或逗号隔开写成一个词({@code 120,64,-35})。快捷工具里是一个字符串,
     * 两种写法都收,也收三个数的数组。
     */
    public static ArgType<BlockPos> cell() {
        ArgumentType<BlockPos> read = ArgType::readCell;
        String hint = "cell: " + POS_SHAPE;
        return new ArgType<>(read, "x y z", "cell", hint, Span.ONE, Item.STRING, true, false, ArgType::stringField,
                ArgType::cellFromJson, pos -> pos.getX() + " " + pos.getY() + " " + pos.getZ())
                .scripted(Shapes.POS.type(), Shapes::value);
    }

    /**
     * 一处({@link Place}):一到三个整数——三个是一格,两个是一列({@code x z}),一个是一个高度——或主人名下的一块区域
     * ({@code ores}、{@code ores/g3})。数字或负号打头的是坐标,别的是区域名。
     */
    public static ArgType<Place> place() {
        ArgumentType<Place> read = ArgType::readPlace;
        String hint = "place: a Pos (a cell, or anything with a pos), a column {x = …, z = …}, a height {y = …}, or an "
                + "area like \"ores\" or \"ores/g3\"";
        return new ArgType<>(read, "place", "place", hint, Span.ONE, Item.STRING, true, false, ArgType::stringField,
                ArgType::placeFromJson, Place::written)
                .scripted(ScriptType.union(Shapes.POS.type(), ScriptType.table(
                                ScriptType.field("x", ScriptType.NUMBER, null), ScriptType.field("z", ScriptType.NUMBER, null)),
                        ScriptType.table(ScriptType.field("y", ScriptType.NUMBER, null)), ScriptType.STRING),
                        Place::value);
    }

    /**
     * 一种方块({@link #idOrTag} 的写法)、一格坐标,或主人名下的一块区域 {@code area:名字}、{@code area:名字/部分}
     * ({@link AreaRef#MARK} 打头:和方块写在一串里,区域名要带记号才分得开)。数字或负号打头的是坐标。一片地方只有区域一种写法:
     * 要一个盒子,先把它框成区域。
     */
    public static ArgType<BlockCellOrArea> blockCellOrArea() {
        ArgumentType<BlockCellOrArea> read = ArgType::readBlockCellOrArea;
        String hint = "block id, #tag, a cell (" + POS_SHAPE + ") or \"area:<name>\"";
        return new ArgType<>(read, "block|cell|area", "block|cell|area", hint, Span.ONE, Item.STRING, true, false,
                ArgType::stringField, ArgType::blockCellOrAreaFromJson, BlockCellOrArea::written)
                .scripted(ScriptType.union(ScriptType.STRING, Shapes.POS.type()),
                        v -> v.cell() != null ? Shapes.value(v.cell()) : v.written());
    }

    /** 方块、格子或区域的 JSON:表是一格,字符串是方块 id、标签或 {@code area:名字}。 */
    private static BlockCellOrArea blockCellOrAreaFromJson(JsonElement value) throws CommandSyntaxException {
        if (value != null && value.isJsonObject()) {
            return new BlockCellOrArea(null, cellFromJson(value), null);
        }
        Object instead = coordinates(value, 3);
        if (instead instanceof java.util.Map<?, ?> pos && pos.size() == 3) {
            throw new WrongShape("a cell here is a Pos with named fields; got " + given(value), instead);
        }
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw REJECTED.create("expected a block id, a #tag, a cell (" + POS_SHAPE + ") or \"area:<name>\"; got "
                    + given(value));
        }
        return whole(ArgType::readBlockCellOrArea, value.getAsString(), "block id, #tag or area:<name>");
    }

    private static BlockCellOrArea readBlockCellOrArea(StringReader reader) throws CommandSyntaxException {
        if (reader.getString().startsWith(AreaRef.MARK, reader.getCursor())) {
            return new BlockCellOrArea(null, null, readMarkedArea(reader));
        }
        if (startsNumber(reader.getString(), reader.getCursor())) {
            return new BlockCellOrArea(null, readCell(reader), null);
        }
        return new BlockCellOrArea(readIdOrTag(reader), null, null);
    }

    /**
     * 几个固定值之一({@link #oneOf} 的写法),或主人名下的一块区域 {@code area:名字}、{@code area:名字/部分}:比如路线要避开的
     * 格子种类与区域写在同一串里({@code --avoid water area:farm})。读出来是写下的原文;区域那一样由用它的一方经
     * {@link AreaRef#marked} 认,和这里同一个读法。
     */
    public static ArgType<String> oneOfOrArea(String... choices) {
        List<String> allowed = List.of(choices);
        String listed = String.join(", ", allowed) + " or " + AreaRef.MARK + "<name>";
        return new ArgType<>(reader -> {
            if (reader.getString().startsWith(AreaRef.MARK, reader.getCursor())) {
                int start = reader.getCursor();
                readMarkedArea(reader);
                return reader.getString().substring(start, reader.getCursor());
            }
            int start = reader.getCursor();
            String value = reader.readUnquotedString();
            if (!allowed.contains(value)) {
                reader.setCursor(start);
                throw NOT_A_CHOICE.createWithContext(reader, listed);
            }
            return value;
        }, String.join("|", allowed) + "|" + AreaRef.MARK + "<name>", "choice|area", "one of " + listed, Item.STRING,
                ArgType::stringField);
    }

    /**
     * 主人名下的一块区域({@link AreaRef}):{@code 名字} 指整块,{@code 名字/部分} 指其中一部分。写法在 {@link AreaRef#parse} 认,
     * 名字不合规矩、编号不像编号当场报;有没有这块区域是用它的动作按主人的存档去认。
     */
    public static ArgType<AreaRef> area() {
        ArgumentType<AreaRef> read = reader -> readAreaRef(reader, reader.getCursor());
        String hint = "area name, or name/part like ores/g3";
        return new ArgType<>(read, "area", "area", hint, Span.ONE, Item.STRING, false, false, ArgType::stringField,
                literal(read, hint, UnaryOperator.identity()), AreaRef::toString);
    }

    /** {@code area:} 打头的一截读到空格为止,名字与编号当场认。 */
    private static AreaRef readMarkedArea(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        reader.setCursor(start + AreaRef.MARK.length());
        return readAreaRef(reader, start);
    }

    /** 从当前位置读一个区域名(带不带部分),读到空格为止;认不了的报错指在 {@code start}。 */
    private static AreaRef readAreaRef(StringReader reader, int start) throws CommandSyntaxException {
        int from = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        try {
            return AreaRef.parse(reader.getString().substring(from, reader.getCursor()));
        } catch (IllegalArgumentException wrong) {
            reader.setCursor(start);
            throw REJECTED.createWithContext(reader, wrong.getMessage());
        }
    }

    private static BlockPos readCell(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        if (!startsNumber(reader.getString(), start)) {
            throw NO_CELL.createWithContext(reader);
        }
        List<Integer> n = readCoordinates(reader, 3);
        if (n.size() != 3) {
            reader.setCursor(start);
            throw NO_CELL.createWithContext(reader);
        }
        if (reader.getString().startsWith(RANGE, reader.getCursor())) {
            throw NOT_ONE_CELL.createWithContext(reader);
        }
        return new BlockPos(n.get(0), n.get(1), n.get(2));
    }

    private static Place readPlace(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        if (!reader.canRead()) {
            throw NO_PLACE.createWithContext(reader);
        }
        if (!startsNumber(reader.getString(), start)) {
            return Place.area(readAreaRef(reader, start));
        }
        List<Integer> n = readCoordinates(reader, 3);
        return switch (n.size()) {
            case 3 -> new Place(n.get(0), n.get(1), n.get(2), null);
            case 2 -> new Place(n.get(0), null, n.get(1), null);
            default -> new Place(null, n.get(0), null, null);
        };
    }

    /**
     * 读一到 {@code max} 个坐标数。第一个数之后的分隔定下写法:逗号就一律逗号({@code 120,64,-35}),空格就一律空格
     * ({@code 120 64 -35});空格隔开时下一个词不是数就停在它前面——那是下一个值或下一个标志。
     */
    private static List<Integer> readCoordinates(StringReader reader, int max) throws CommandSyntaxException {
        List<Integer> out = new ArrayList<>(max);
        out.add(readCoordinate(reader));
        char separator = 0;
        while (out.size() < max && reader.canRead()) {
            char c = reader.peek();
            boolean comma = c == COMMA && separator != ' ';
            boolean space = c == ' ' && separator != COMMA && startsNumber(reader.getString(), reader.getCursor() + 1);
            if (!comma && !space) {
                break;
            }
            separator = c;
            reader.skip();
            out.add(readCoordinate(reader));
        }
        return out;
    }

    /** {@code text} 在 {@code at} 处是一个数的开头:一位数字,或负号接一位数字。 */
    private static boolean startsNumber(String text, int at) {
        if (at >= text.length()) {
            return false;
        }
        char c = text.charAt(at);
        return Character.isDigit(c) || (c == '-' && at + 1 < text.length() && Character.isDigit(text.charAt(at + 1)));
    }

    /**
     * 一个坐标:可带负号的一串数字。只收整数,写成小数或别的字就报"要整数的一格"——不借 Brigadier 的 {@code readInt},
     * 它把 {@code .} 也读进来,报的是另一句。
     */
    private static int readCoordinate(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        if (reader.canRead() && reader.peek() == '-') {
            reader.skip();
        }
        int firstDigit = reader.getCursor();
        while (reader.canRead() && Character.isDigit(reader.peek())) {
            reader.skip();
        }
        int digits = reader.getCursor() - firstDigit;
        boolean decimal = reader.canRead() && reader.peek() == '.'
                && !reader.getString().startsWith(RANGE, reader.getCursor());
        if (digits == 0 || digits > MAX_DIGITS || decimal) {
            reader.setCursor(start);
            throw NO_CELL.createWithContext(reader);
        }
        return Integer.parseInt(reader.getString().substring(start, reader.getCursor()));
    }

    /**
     * 同一种写法,读通之后交给 {@code parse} 认:认不了抛出的 {@link IllegalArgumentException} 的话就是这个值的报错,
     * 位置指在它的开头。命令行、快捷工具都经这一处,读出来的都是认过的值。
     *
     * @param kind    帮助与标志用法里的称呼,也是它的对象类别({@link #noun()})
     * @param hint    帮助里的完整称呼
     * @param parse   把这种写法读出的值认成要的东西
     * @param unparse 认好的东西写回这种写法的值:{@code parse} 读回来是同一个
     */
    public <R> ArgType<R> as(String kind, String hint, Function<T, R> parse, Function<R, T> unparse) {
        ArgumentType<R> judged = reader -> {
            int start = reader.getCursor();
            T raw = brigadier.parse(reader);
            try {
                return parse.apply(raw);
            } catch (IllegalArgumentException wrong) {
                reader.setCursor(start);
                throw REJECTED.createWithContext(reader, wrong.getMessage());
            }
        };
        FromJson<R> fromJson = value -> {
            T raw = json.read(value);
            try {
                return parse.apply(raw);
            } catch (IllegalArgumentException wrong) {
                throw REJECTED.create(wrong.getMessage());
            }
        };
        ArgType<R> judgedType = new ArgType<>(judged, kind, kind, hint, span, item, words, flagSwitch, schema,
                fromJson, value -> written.apply(unparse.apply(value)));
        judgedType.raw = value -> raw.apply(unparse.apply(value));
        judgedType.script = script;
        if (toScript != null) {
            judgedType.toScript = value -> toScript.apply(unparse.apply(value));
        }
        return judgedType;
    }

    /**
     * 一只实体({@link EntityRef}):{@code scan entities} 列出的运行期编号,或它的 UUID。帮助与报错只说编号——那是她的写法;
     * UUID 是受理之后写进重放那一行的写法,读得回来就行。
     */
    public static ArgType<EntityRef> entity() {
        ArgumentType<EntityRef> read = ArgType::readEntity;
        String hint = "entity: its id as numen.scan.entities lists it, or the Entity itself";
        return new ArgType<>(read, "entity", "entity", hint, Span.ONE, Item.STRING, false, false,
                ArgType::stringField, ArgType::entityFromJson, EntityRef::written)
                .scripted(ScriptType.union(ScriptType.INTEGER, Shapes.ENTITY.type()),
                        ref -> ref.id() != null ? (Object) (long) ref.id() : ref.uuid().toString());
    }

    private static EntityRef readEntity(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        String raw = reader.getString().substring(start, reader.getCursor());
        if (UUID_TEXT.matcher(raw).matches()) {
            return new EntityRef(null, java.util.UUID.fromString(raw));
        }
        if (!raw.isEmpty() && raw.chars().allMatch(Character::isDigit) && raw.length() <= MAX_DIGITS) {
            return EntityRef.id(Integer.parseInt(raw));
        }
        reader.setCursor(start);
        throw NO_ENTITY.createWithContext(reader);
    }

    /**
     * 一串同一种的值:命令行上是空格隔开的一个个值({@code iron_ore deepslate_iron_ore}),每个都按 {@code element} 的读法读,
     * 读到行尾或下一个标志({@code --} 打头)为止,所以它既能是动作的最后一个位置参数,也能是一个标志
     * ({@code --item-ids iron_ingot raw_iron --area farm});快捷工具里是一个 JSON 数组,每一项按 {@code element}
     * 读 JSON 值的规矩读,一项占几个词的(坐标)各项接成一行再读。至少一个。一项只能是一个值:整数、词、id、id 或标签、坐标、一处、
     * 方块或坐标格或区域、区域、一只实体、几个固定值之一(或区域)、一个值。
     */
    public static <T> ArgType<List<T>> list(ArgType<T> element) {
        if (element.item == Item.NONE) {
            throw new IllegalArgumentException(element.kind + " 不能做一串值里的一项:它不止一个值,或 JSON 数组里没有对应的项");
        }
        String hint = element.hint + "; one, or several as a list {a, b}";
        ArgumentType<List<T>> read = reader -> {
            List<T> values = new ArrayList<>();
            values.add(element.read(reader));
            while (reader.canRead()) {
                if (reader.peek() != ' ') {
                    throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherExpectedArgumentSeparator()
                            .createWithContext(reader);
                }
                if (reader.getString().startsWith(FlagsArgument.PREFIX, reader.getCursor() + 1)) {
                    break;
                }
                reader.skip();
                values.add(element.read(reader));
            }
            return List.copyOf(values);
        };
        FromJson<List<T>> fromJson = value -> {
            if (value == null || !value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
                throw NOT_A_VALUE.create("a list: " + hint);
            }
            List<T> values = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) {
                try {
                    values.add(element.fromJson(item));
                } catch (WrongShape bad) {
                    // 一项是个数而这一项不收数,整张列表又是三个数:她写的是一处旧样子的坐标 {120, 64, -35},不是三处
                    Object whole = coordinates(value, 3);
                    if (item.isJsonPrimitive() && whole instanceof java.util.Map<?, ?> pos && pos.size() == 3) {
                        throw new WrongShape("a position is one table with named fields; got " + given(value), whole);
                    }
                    throw bad;
                }
            }
            return List.copyOf(values);
        };
        ArgType<List<T>> list = new ArgType<>(read, element.kind + "...", element.noun, hint, Span.SEVERAL, Item.NONE,
                false, false,
                (s, name, desc, required) -> {
                    boolean integers = element.item == Item.INTEGER;
                    if (required && integers) s.intArray(name, desc, 1, 0);
                    else if (required) s.stringArray(name, desc, 1);
                    else if (integers) s.optionalIntArray(name, desc, 0, 0);
                    else s.optionalStringArray(name, desc);
                }, fromJson, values -> values.stream().map(element::write).collect(Collectors.joining(" ")));
        list.itemWords = element.words;
        list.element = element;
        list.script = ScriptType.union(element.script, ScriptType.listOf(element.script));
        return list;
    }

    private static void stringField(Schema.Builder s, String name, String desc, boolean required) {
        if (required) s.string(name, desc);
        else s.optionalString(name, desc);
    }

    /** 命令行上的读法。 */
    ArgumentType<T> brigadier() {
        return brigadier;
    }

    /** 从命令行当前位置读一个值(标志的值经这里)。 */
    T read(StringReader reader) throws CommandSyntaxException {
        return brigadier.parse(reader);
    }

    /** 读好的值写回命令行上的样子:同一个类型再读一遍,得到的是同一个值。 */
    String write(T value) {
        return written.apply(value);
    }

    /**
     * 读好的值写成脚本里的一个值,和脚本这个前端读回来的是同一个:开关是布尔,整数与小数是数,一串值是一张表,占几个词而全是数的
     * (一格坐标)是一张数的表,别的是它的文字。
     */
    Object plain(T value) {
        if (flagSwitch || value instanceof Number) {
            return value;
        }
        if (element != null) {
            return ((List<?>) value).stream().map(this::plainItem).toList();
        }
        if (toScript != null) {
            return toScript.apply(value);
        }
        return raw.apply(value);
    }

    /** 脚本里它的类型;一串值是"一项或一张表"。 */
    ScriptType script() {
        return script;
    }

    /** 一串值的一项的脚本类型;不是一串值是它自己的。 */
    ScriptType itemScript() {
        return element != null ? element.script : script;
    }

    @SuppressWarnings("unchecked")
    private <E> Object plainItem(Object item) {
        return ((ArgType<E>) element).plain((E) item);
    }

    /** 快捷工具的 JSON 值:字面值写成它在命令行上的样子,用同一个读法整段读完;一串值({@link #list})逐个这样读。 */
    T fromJson(JsonElement value) throws CommandSyntaxException {
        return json.read(value);
    }

    /** 类型的名字,比如 {@code integer};用法里写它。 */
    String kind() {
        return kind;
    }

    /** 它的对象类别:登记时比"位置参数是不是只有一类对象"就比它;一串值的类别是它一项的类别。 */
    String noun() {
        return noun;
    }

    /** 帮助里的完整称呼,比如 {@code integer 1-1200}。 */
    String hint() {
        return hint;
    }

    /** 一个值在命令行上占多宽。 */
    Span span() {
        return span;
    }

    /** 是一串值,而一项本身占几个词(一串格子)。 */
    boolean itemTakesWords() {
        return itemWords;
    }

    /** 是开关:只做标志,不跟值。 */
    boolean isSwitch() {
        return flagSwitch;
    }

    void addTo(Schema.Builder builder, String name, String description, boolean required) {
        schema.add(builder, name, description, required);
    }
}
