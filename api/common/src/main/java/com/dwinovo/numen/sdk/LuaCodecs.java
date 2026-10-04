package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本的值与 Java 的值怎么互转,只在这里:每种 Java 类型一个 {@link Codec},它同时说出自己在签名里写成什么,所以帮助里的类型与真正
 * 收的、交回的数据是同一个来源。
 *
 * <h2>有哪些</h2>
 * <ul>
 *   <li>字符串、整数、小数、布尔、{@code void};枚举是它的几个小写名字之一;{@link ResourceLocation} 是 id,不写命名空间就是
 *       {@code minecraft:};一种物品、一种方块({@code Item}、{@code Block} 的 Java 类型)是它的 id,认不出的读不成。</li>
 *   <li>位置只有一种写法 {@code Pos}:{@link BlockPos} 是一格(读到小数按所在的一格算,任何带 {@code pos} 的表——方块、实体、掉落物——
 *       放在一格的地方就是它的 {@code pos}),{@link Vec3} 是身体与实体的位置(两位小数)。{@link EntityRef}、{@link Place}、
 *       {@link Target} 各有一种写法,见它们自己。</li>
 *   <li>{@code List<T>}、{@code Map<String, T>};record 是一张表,组件名从驼峰换成下划线就是字段名,{@code Optional} 组件可以没有,
 *       类名是 {@code <名字空间>.<类名>}(第一个用到它的函数的名字空间;{@link #SHARED} 里共用的几种没有名字空间)。</li>
 *   <li>插件自己的类型:{@code numen.codec(MyThing.class, codec)}。</li>
 * </ul>
 */
public final class LuaCodecs {

    private LuaCodecs() {}

    /** 按 Java 类登记的;record 与枚举用到时才造,也记在这里。 */
    private static final Map<Class<?>, Codec<?>> BY_CLASS = new ConcurrentHashMap<>();
    /** 声明了的类,按名字。 */
    private static final Map<String, ScriptType.Class> CLASSES = new TreeMap<>();
    /** 类名归谁(一个 Java 类型);同一个名字给了两个类型是硬错误。 */
    private static final Map<String, Class<?>> NAMED = new TreeMap<>();

    // ---- 共用的类 ----

    /** 一个位置;方法与运算写在内置模块 {@code numen.shape} 里。 */
    public static final ScriptType.Class POS = new ScriptType.Class("Pos",
            "A position. A cell's x, y, z are whole numbers; a body's or an entity's are decimals. Wherever a Pos is "
                    + "taken, anything with a pos field (a Block, an Entity, an Item) goes too, and decimals mean the "
                    + "cell they fall in (rounded down, as Minecraft does). p + q and p - q add and subtract, p == q "
                    + "compares; numen.shape.pos(x, y, z) makes one.",
            null, List.of(ScriptType.field("x", ScriptType.NUMBER, null), ScriptType.field("y", ScriptType.NUMBER, null),
                    ScriptType.field("z", ScriptType.NUMBER, null))).methodsIn("numen.shape");

    /** 失败的调用抛出的错误值。 */
    public static final ScriptType.Class ERROR = new ScriptType.Class("Error",
            "What a failed call raises. local ok, err = pcall(numen.work.dig, b) catches it; tostring(err) reads it. "
                    + "raise(kind, message, hint) raises one of your own.",
            null, List.of(
                    ScriptType.field("kind", ScriptType.STRING, "bad_argument, no_function, not_found, out_of_reach, "
                            + "no_path, no_material, needs_consent, denied, interrupted, timeout or failed."),
                    ScriptType.field("message", ScriptType.STRING, "What went wrong."),
                    ScriptType.optional("hint", ScriptType.STRING, "A next line to run."),
                    ScriptType.optional("fn", ScriptType.STRING, "The function that failed, numen.work.dig."),
                    ScriptType.optional("data", new ScriptType.Simple("table"), "What the call knew when it failed (the "
                            + "nearest cell out of reach …).")));

    /** 一串格:每一格是一个 Block(那一格要放的方块)或一个 Pos(只是那一格);方法写在内置模块 {@code numen.shape} 里。 */
    public static final ScriptType.Class CELLS = ScriptType.Class.listOf("Cells",
            "A list of cells, each a Block (the block for that cell, written as /setblock takes it) or a Pos (just the "
                    + "cell). numen.shape draws them; numen.shape.cells(list) makes Cells of any such list.",
            ScriptType.union(new ScriptType.Named("Block"), new ScriptType.Named("Pos"))).methodsIn("numen.shape");

    static {
        declare(POS, LuaCodecs.class);
        declare(ERROR, LuaCodecs.class);
        declare(CELLS, LuaCodecs.class);
        BY_CLASS.put(String.class, Builtin.STRING);
        BY_CLASS.put(Integer.class, Builtin.INTEGER);
        BY_CLASS.put(int.class, Builtin.INTEGER);
        BY_CLASS.put(Long.class, Builtin.LONG);
        BY_CLASS.put(long.class, Builtin.LONG);
        BY_CLASS.put(Double.class, Builtin.DOUBLE);
        BY_CLASS.put(double.class, Builtin.DOUBLE);
        BY_CLASS.put(Boolean.class, Builtin.BOOLEAN);
        BY_CLASS.put(boolean.class, Builtin.BOOLEAN);
        BY_CLASS.put(Void.class, Builtin.NOTHING);
        BY_CLASS.put(void.class, Builtin.NOTHING);
        BY_CLASS.put(ResourceLocation.class, Builtin.ID);
        BY_CLASS.put(net.minecraft.world.item.Item.class, Builtin.ITEM);
        BY_CLASS.put(net.minecraft.world.level.block.Block.class, Builtin.BLOCK);
        BY_CLASS.put(BlockPos.class, Positions.CELL);
        BY_CLASS.put(Vec3.class, Positions.EXACT);
        BY_CLASS.put(EntityRef.class, EntityRef.CODEC);
        BY_CLASS.put(Place.class, Place.CODEC);
        BY_CLASS.put(Target.class, Target.CODEC);
        BY_CLASS.put(CellOrEntity.class, CellOrEntity.CODEC);
        BY_CLASS.put(BlockAt.class, record(BlockAt.class, "Block"));
        BY_CLASS.put(EntityInfo.class, record(EntityInfo.class, "Entity"));
        BY_CLASS.put(ItemInfo.class, record(ItemInfo.class, "Item"));
    }

    /** 帮助与系统提示里列出的共用类,按这个顺序:索引里有它们,一组的帮助不再重复。 */
    public static final List<String> SHARED = List.of("Pos", "Block", "Entity", "Item", "Cells", "Error");

    /**
     * 登记一种类型的值转换(插件经 {@code NumenApi.codec} 来)。它声明的类一并登记。
     *
     * @throws IllegalArgumentException 这个类型已经有了,或它声明的类名已经被别的类型占了
     */
    public static synchronized <T> void register(Class<T> type, Codec<T> codec) {
        if (BY_CLASS.containsKey(type)) {
            throw new IllegalArgumentException(type.getName() + " already has a codec");
        }
        for (ScriptType.Class c : codec.classes()) {
            declare(c, type);
        }
        BY_CLASS.put(type, codec);
    }

    /** 声明一个类;名字已经归了另一个类型是硬错误。 */
    static synchronized void declare(ScriptType.Class c, Class<?> owner) {
        Class<?> had = NAMED.putIfAbsent(c.name(), owner);
        if (had != null && had != owner) {
            throw new IllegalArgumentException("the Lua class name " + c.name() + " is taken by " + had.getName()
                    + "; " + owner.getName() + " cannot have it too");
        }
        CLASSES.put(c.name(), c);
    }

    /** 此刻声明了的类,按名字。 */
    public static synchronized Map<String, ScriptType.Class> classes() {
        return Map.copyOf(CLASSES);
    }

    /** 这个名字的类;没有是 null。 */
    public static synchronized ScriptType.Class classNamed(String name) {
        return CLASSES.get(name);
    }

    /**
     * 这个 Java 类型的值转换:登记了的、内置的,或按它的样子现造(record、枚举、{@code List}、{@code Map<String, T>})。
     *
     * @param namespace 现造 record 时它的类名用哪个名字空间
     * @throws IllegalArgumentException 这种类型没有值转换({@code Optional} 只能是 record 的组件)
     */
    public static Codec<?> of(Type type, String namespace) {
        if (type instanceof Class<?> c) {
            Codec<?> known = BY_CLASS.get(c);
            if (known != null) {
                return known;
            }
            if (c.isEnum()) {
                return BY_CLASS.computeIfAbsent(c, LuaCodecs::enumCodec);
            }
            if ((c.isSealed() && c.isInterface()) || c.isRecord()) {
                // 现造时会为它的字段(或允许的几种)再找值转换:不能放在 computeIfAbsent 里递归
                synchronized (LuaCodecs.class) {
                    Codec<?> made = BY_CLASS.get(c);
                    if (made == null) {
                        made = c.isRecord() ? record(c.asSubclass(Record.class), className(c, namespace))
                                : new SealedCodec(c, namespace);
                        BY_CLASS.put(c, made);
                    }
                    return made;
                }
            }
        }
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw) {
            Type[] args = p.getActualTypeArguments();
            if (raw == List.class) {
                return new ListCodec<>(of(args[0], namespace));
            }
            if (raw == Map.class && args[0] == String.class) {
                return new MapCodec<>(of(args[1], namespace));
            }
            if (raw == Optional.class) {
                throw new IllegalArgumentException("Optional is only for a record component (an option, or a field "
                        + "that may be missing), not " + type.getTypeName());
            }
        }
        throw new IllegalArgumentException("no codec for " + type.getTypeName() + ": use a record, an enum, a "
                + "List or Map<String, …> of such, a built-in value, or register one with numen.codec");
    }

    /**
     * 一个 record 在签名里的类名:引擎自己的不带前缀(和 {@code Pos}、{@code Block} 一样),插件的带它的名字空间({@code tlm.Maid}),
     * 两个插件各有一个 {@code Machine} 也不相撞。
     */
    private static String className(Class<?> c, String namespace) {
        return namespace.equals("numen") ? c.getSimpleName() : namespace + "." + c.getSimpleName();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Codec<?> enumCodec(Class<?> c) {
        return new EnumCodec(c);
    }

    private static <R extends Record> RecordCodec<R> record(Class<R> type, String name) {
        return new RecordCodec<>(type, name);
    }

    /**
     * 一个 Java 值写成脚本的值,按它的运行期类型找值转换:字符串、数、布尔、枚举、列表、名字到值的表原样往里走,别的用它登记了的
     * 那一个。错误值的 {@code data} 与提示里写的调用({@link Call#of})经这里。
     *
     * @throws IllegalStateException 这个类型还没有值转换(没有函数用过它,也没登记)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object encode(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Boolean b -> b;
            case Double d -> d;
            case Float f -> (double) f;
            case Number n -> n.longValue();
            case Enum<?> e -> e.name().toLowerCase(Locale.ROOT);
            case com.dwinovo.numen.agent.script.JsonValues.Folded f -> f;
            case List<?> list -> {
                List<Object> out = new ArrayList<>(list.size());
                list.forEach(v -> out.add(encode(v)));
                yield out;
            }
            case Map<?, ?> map -> {
                // 自己有先后的表(LinkedHashMap、SortedMap)照它的先后;没有的(Map.of、HashMap)按键排:同一个值每次写成同一行字
                Map<String, Object> out = map instanceof LinkedHashMap<?, ?> || map instanceof java.util.SortedMap<?, ?>
                        ? new LinkedHashMap<>() : new java.util.TreeMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), encode(v)));
                yield out;
            }
            default -> {
                // 按它的类往上找:一格的可变子类(BlockPos.MutableBlockPos)写起来和一格一样
                Codec codec = null;
                for (Class<?> c = value.getClass(); codec == null && c != null; c = c.getSuperclass()) {
                    codec = BY_CLASS.get(c);
                }
                if (codec == null) {
                    throw new IllegalStateException(value.getClass().getName() + " has no codec yet: return it from "
                            + "an API function or register one with numen.codec");
                }
                yield codec.encode(value);
            }
        };
    }

    /** 一个 Java 值写成的字面量({@code {x = 1, y = 2, z = 3}}):报错、提示里写一个值经这里。 */
    public static String literal(Object value) {
        return ScriptEngine.IN_USE.value(encode(value));
    }

    /** 驼峰写成下划线:{@code maxHp} 是 {@code max_hp}。 */
    static String snake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char ch = camel.charAt(i);
            if (Character.isUpperCase(ch)) {
                sb.append('_').append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 一张表:名字到值的表;空表读进来是空列表,也算。不是表是 null。 */
    static Map<String, Object> table(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return null;
    }

    // ---- 几种现造的 ----

    /** 一串同一种的值。 */
    private record ListCodec<T>(Codec<T> item) implements Codec<List<T>> {

        @Override
        public ScriptType type() {
            return ScriptType.listOf(item.type());
        }

        @Override
        public List<T> decode(Object value) {
            if (!(value instanceof List<?> list)) {
                throw new BadValue("expected a list " + ScriptEngine.IN_USE.typeText(type()) + "; got "
                        + BadValue.given(value));
            }
            List<T> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                try {
                    out.add(item.decode(list.get(i)));
                } catch (BadValue bad) {
                    throw bad.in("item " + (i + 1));
                }
            }
            return List.copyOf(out);
        }

        @Override
        public Object encode(List<T> value) {
            List<Object> out = new ArrayList<>(value.size());
            value.forEach(v -> out.add(item.encode(v)));
            return out;
        }
    }

    /** 名字到同一种值的表。 */
    private record MapCodec<T>(Codec<T> value) implements Codec<Map<String, T>> {

        @Override
        public ScriptType type() {
            return ScriptType.mapOf(value.type());
        }

        @Override
        public Map<String, T> decode(Object given) {
            Map<String, Object> table = table(given);
            if (table == null) {
                throw new BadValue("expected a table of names to values; got " + BadValue.given(given));
            }
            Map<String, T> out = new LinkedHashMap<>();
            table.forEach((k, v) -> {
                try {
                    out.put(k, value.decode(v));
                } catch (BadValue bad) {
                    throw bad.in("field '" + k + "'");
                }
            });
            return out;
        }

        @Override
        public Object encode(Map<String, T> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k, value.encode(v)));
            return out;
        }
    }

    /**
     * 几种之一:一个 sealed 接口,它允许的每一种(record)各有自己的值转换;写回去按值的运行期类型,读进来依次试,第一种读得成的就是它。
     * 签名里写成几种之一({@code Entity|Item})。
     */
    private static final class SealedCodec implements Codec<Object> {

        private final List<Codec<Object>> options = new ArrayList<>();
        private final Map<Class<?>, Codec<Object>> byClass = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        SealedCodec(Class<?> sealed, String namespace) {
            for (Class<?> permitted : sealed.getPermittedSubclasses()) {
                Codec<Object> codec = (Codec<Object>) of(permitted, namespace);
                options.add(codec);
                byClass.put(permitted, codec);
            }
        }

        @Override
        public ScriptType type() {
            return new ScriptType.Union(options.stream().map(Codec::type).toList());
        }

        @Override
        public Object decode(Object value) {
            BadValue last = null;
            for (Codec<Object> codec : options) {
                try {
                    return codec.decode(value);
                } catch (BadValue bad) {
                    last = bad;
                }
            }
            throw last;
        }

        @Override
        public Object encode(Object value) {
            return byClass.get(value.getClass()).encode(value);
        }
    }

    /** 几个固定值之一:枚举的名字,小写。 */
    private static final class EnumCodec<E extends Enum<E>> implements Codec<E> {

        private final Class<E> type;
        private final List<String> names = new ArrayList<>();

        EnumCodec(Class<E> type) {
            this.type = type;
            for (E e : type.getEnumConstants()) {
                names.add(e.name().toLowerCase(Locale.ROOT));
            }
        }

        @Override
        public ScriptType type() {
            return ScriptType.choice(names);
        }

        @Override
        public E decode(Object value) {
            int at = value instanceof String s ? names.indexOf(s) : -1;
            if (at < 0) {
                throw new BadValue("expected one of " + String.join(", ", names) + "; got " + BadValue.given(value));
            }
            return type.getEnumConstants()[at];
        }

        @Override
        public Object encode(E value) {
            return names.get(value.ordinal());
        }
    }
}
