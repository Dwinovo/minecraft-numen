package com.dwinovo.numen.agent.script;

import java.util.List;

/**
 * 一个 API 值的类型,和写它的语言无关:参数收什么、函数返回什么、一张表有哪些字段。帮助与系统提示里的签名由它现算
 * ({@link ScriptEngine#typeText}、{@link ScriptEngine#classText}),写成语言自己的类型注解(Lua 是 LuaLS 的 {@code ---@param}
 * 那一套),不手写第二份。
 */
public sealed interface ScriptType {

    ScriptType INTEGER = new Simple("integer");
    ScriptType NUMBER = new Simple("number");
    ScriptType STRING = new Simple("string");
    ScriptType BOOLEAN = new Simple("boolean");
    /** 不返回值。 */
    ScriptType NOTHING = new Simple("nil");

    /** 语言自带的一种:整数、数、字符串、布尔、nil。 */
    record Simple(String name) implements ScriptType {}

    /** 一个有名字的类({@link Class}),按名字引用。 */
    record Named(String name) implements ScriptType {}

    /** 一串同一种的值。 */
    record ListOf(ScriptType item) implements ScriptType {}

    /** 名字到同一种值的表({@code table<string, T>}):键不固定的,如方块状态。 */
    record MapOf(ScriptType value) implements ScriptType {}

    /** 几种之一。 */
    record Union(List<ScriptType> options) implements ScriptType {}

    /** 几个固定的字符串之一。 */
    record Choice(List<String> values) implements ScriptType {}

    /** 一张就地写出字段的表(选项表、一个函数自己的结果)。 */
    record Table(List<Field> fields) implements ScriptType {}

    /**
     * 表的一个字段。
     *
     * @param optional 可以没有
     * @param doc      一句说明;没有是 null
     */
    record Field(String name, ScriptType type, boolean optional, String doc) {}

    /**
     * 一个有名字的类:名字、一句说明、继承的类(没有是 null)、字段。
     *
     * @param items 一串值的类({@code Cells}):每一项是什么;一张带字段的表是 null
     * @param home  它的方法写在哪个模块里({@code numen.shape}):那个模块里与类同名的表是它的值的元表(方法、运算);只是数据的是 null
     */
    record Class(String name, String doc, String parent, List<Field> fields, ScriptType items, String home) {

        public Class {
            fields = List.copyOf(fields);
        }

        public Class(String name, String doc, String parent, List<Field> fields) {
            this(name, doc, parent, fields, null, null);
        }

        /** 一串同一种值的类:{@code Cells} 是一串 Block 或 Pos。 */
        public static Class listOf(String name, String doc, ScriptType items) {
            return new Class(name, doc, null, List.of(), items, null);
        }

        /** 同一个类,方法写在模块 {@code home} 里。 */
        public Class methodsIn(String home) {
            return new Class(name, doc, parent, fields, items, home);
        }

        /** 按名字引用它。 */
        public Named type() {
            return new Named(name);
        }
    }

    /**
     * 这个类型的一个样子:数是 0、字符串空、布尔 false、列表里有一项、表与类按字段造(可以没有的字段也造上),几种之一取第一种,nil 是
     * null。只读不跑一段正文时(帮助里的例子)调用返回它:取字段、取第一项、循环一遍的写法都照样读得通。
     *
     * @param classes 按名字找类;找不到的类造成空表
     */
    static Object sample(ScriptType type, java.util.function.Function<String, Class> classes) {
        return switch (type) {
            case Simple s -> switch (s.name()) {
                case "integer" -> 0L;
                case "number" -> 0.0;
                case "string" -> "";
                case "boolean" -> false;
                case "nil" -> null;
                default -> new java.util.LinkedHashMap<String, Object>();
            };
            case Named n -> {
                Class c = classes.apply(n.name());
                if (c != null && c.items() != null) {
                    Object item = sample(c.items(), classes);
                    yield item == null ? java.util.List.of() : java.util.List.of(item);
                }
                java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
                if (c != null) {
                    if (c.parent() != null && sample(new Named(c.parent()), classes) instanceof java.util.Map<?, ?> up) {
                        up.forEach((k, v) -> out.put(String.valueOf(k), v));
                    }
                    c.fields().forEach(f -> out.put(f.name(), sample(f.type(), classes)));
                }
                yield out;
            }
            case ListOf l -> {
                Object item = sample(l.item(), classes);
                yield item == null ? java.util.List.of() : java.util.List.of(item);
            }
            case MapOf m -> new java.util.LinkedHashMap<String, Object>();
            case Union u -> sample(u.options().get(0), classes);
            case Choice c -> c.values().get(0);
            case Table t -> {
                java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
                t.fields().forEach(f -> out.put(f.name(), sample(f.type(), classes)));
                yield out;
            }
        };
    }

    static ScriptType named(Class c) {
        return c.type();
    }

    static ScriptType listOf(ScriptType item) {
        return new ListOf(item);
    }

    static ScriptType mapOf(ScriptType value) {
        return new MapOf(value);
    }

    static ScriptType union(ScriptType... options) {
        return new Union(List.of(options));
    }

    static ScriptType choice(List<String> values) {
        return new Choice(List.copyOf(values));
    }

    static ScriptType table(Field... fields) {
        return new Table(List.of(fields));
    }

    static Field field(String name, ScriptType type, String doc) {
        return new Field(name, type, false, doc);
    }

    static Field optional(String name, ScriptType type, String doc) {
        return new Field(name, type, true, doc);
    }
}
