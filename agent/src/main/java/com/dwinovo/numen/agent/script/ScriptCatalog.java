package com.dwinovo.numen.agent.script;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 脚本里能调的:API 登记处的每个动作一个宿主函数 {@code 名字空间.组.动词}(第 ① 层,{@code numen.work.dig}),加上模块(用脚本语言写的
 * 库,按名字直接用:{@code my.lumber.chop(…)};和组同名的模块给那一组加函数)。目录由命令层现算交来(见 api 的
 * {@code NumenCli.scriptCatalog}),这里不另记一份动作表——脚本调一个函数,背后就是那一个动作。
 *
 * @param groups  组的全名({@code numen.work})→ 动词名 → 这个动词的函数怎么交回结果
 * @param modules 模块从哪来:用到时才问,所以每次运行读到的是此刻的正文
 * @param classes 声明了的类,按名字:返回值里哪些是带方法的值({@link ScriptType.Class#home})由它们说
 */
public record ScriptCatalog(Map<String, Map<String, Verb>> groups, ModuleSource modules,
                            Map<String, ScriptType.Class> classes) {

    /** 没有声明任何类的目录。 */
    public ScriptCatalog(Map<String, Map<String, Verb>> groups, ModuleSource modules) {
        this(groups, modules, Map.of());
    }

    /** 模块从哪来。两个方法都可能在脚本的线程上被调。 */
    public interface ModuleSource {

        /** 叫这个名字({@code numen.work}、{@code my.lumber})的模块此刻的正文;没有是 null。 */
        String code(String name);

        /** 有哪些模块,按名字排。 */
        java.util.List<String> names();

        /** 一个模块都没有。 */
        ModuleSource NONE = new ModuleSource() {
            @Override
            public String code(String name) {
                return null;
            }

            @Override
            public java.util.List<String> names() {
                return java.util.List.of();
            }
        };
    }

    /**
     * 一个动词的函数。
     *
     * @param returns   动作登记时声明的回值:回执 {@code data} 里的这个键直接作函数的返回值(查询拿来就能循环);
     *                  没声明是 null,成功返回回执数据、失败抛错
     * @param echoed    它成功的调用的参数原样留在脚本回执的 {@code data} 里({@link ScriptCall#ECHOED}):对话流据此画它
     * @param options   它的选项名:写在最后的一张表,键全是这些名字时才是选项表,否则它是一个对象(一个 Pos、一只实体);
     *                  不知道参数表的(只说返回项的那种)是 null,写在最后的名字表都当选项表
     * @param positions 按顺序的对象最多几个;最后一个收一串的是 {@link Integer#MAX_VALUE}
     * @param sample    它返回值的样子(按声明的返回类型现造,数都是 0、列表都是空的):只读不跑一段正文时,调用返回它,取字段的写法
     *                  照样读得通;没有是 null
     * @param type      它返回的值的类型(声明了返回项的,是那一项的类型);没声明是 null
     */
    public record Verb(String returns, boolean echoed, java.util.Set<String> options, int positions, Object sample,
                       ScriptType type) {

        public Verb {
            options = options == null ? null : java.util.Set.copyOf(options);
        }

        /** 只说返回项的那种(不知道参数表):写在最后的名字表都是选项表。 */
        public Verb(String returns) {
            this(returns, false, null, Integer.MAX_VALUE, null, null);
        }

        /**
         * 写在最后的这张名字表是不是选项表:空表是;键全是选项名是;按顺序的对象已经给满了(再没有能收它的位置)也是——写错了选项名
         * 的,读参数那一处会说是哪个。别的(一个 Pos、一个方块、一只实体)是一个对象。
         */
        public boolean optionsTable(java.util.Map<?, ?> table, int objectsBefore) {
            if (options == null) {
                return true;
            }
            return table.isEmpty() || objectsBefore >= positions
                    || table.keySet().stream().allMatch(k -> options.contains(String.valueOf(k)));
        }
    }

    public ScriptCatalog {
        TreeMap<String, Map<String, Verb>> copy = new TreeMap<>();
        groups.forEach((group, verbs) -> copy.put(group, Collections.unmodifiableMap(new TreeMap<>(verbs))));
        groups = Collections.unmodifiableMap(copy);
        classes = Map.copyOf(classes);
    }

    /**
     * 一个返回值按它的类型标出带方法的值:类型里遇到方法写在模块里的类({@link ScriptType.Class#home}),那一处的值包成
     * {@code wrap.apply(类, 值)};列表与表照类型往里走。认不出的值(类型与值对不上)原样留着。
     */
    public Object mark(Object value, ScriptType type, java.util.function.BiFunction<ScriptType.Class, Object, Object> wrap) {
        if (value == null || type == null) {
            return value;
        }
        return switch (type) {
            case ScriptType.Named n -> {
                ScriptType.Class c = classes.get(n.name());
                if (c == null) {
                    yield value;
                }
                Object inner = value;
                if (c.items() != null && value instanceof java.util.List<?> list) {
                    inner = list.stream().map(v -> mark(v, c.items(), wrap)).toList();
                } else if (value instanceof Map<?, ?> map) {
                    inner = fields(map, c, wrap);
                }
                yield c.home() == null ? inner : wrap.apply(c, inner);
            }
            case ScriptType.ListOf l -> value instanceof java.util.List<?> list
                    ? list.stream().map(v -> mark(v, l.item(), wrap)).toList() : value;
            case ScriptType.Table t -> value instanceof Map<?, ?> map ? fields(map, t.fields(), wrap) : value;
            case ScriptType.Union u -> {
                for (ScriptType option : u.options()) {
                    if (fits(value, option)) {
                        yield mark(value, option, wrap);
                    }
                }
                yield value;
            }
            case ScriptType.Simple s -> value;
            case ScriptType.Choice c -> value;
        };
    }

    /** 一个类的字段(连同继承来的)按各自的类型标。 */
    private Map<String, Object> fields(Map<?, ?> map, ScriptType.Class c,
                                       java.util.function.BiFunction<ScriptType.Class, Object, Object> wrap) {
        java.util.List<ScriptType.Field> all = new java.util.ArrayList<>(c.fields());
        for (ScriptType.Class up = c.parent() == null ? null : classes.get(c.parent()); up != null;
             up = up.parent() == null ? null : classes.get(up.parent())) {
            all.addAll(up.fields());
        }
        return fields(map, all, wrap);
    }

    private Map<String, Object> fields(Map<?, ?> map, java.util.List<ScriptType.Field> fields,
                                       java.util.function.BiFunction<ScriptType.Class, Object, Object> wrap) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> {
            ScriptType field = fields.stream().filter(f -> f.name().equals(String.valueOf(k))).map(ScriptType.Field::type)
                    .findFirst().orElse(null);
            out.put(String.valueOf(k), mark(v, field, wrap));
        });
        // 收起来的字段同样按类型标,仍旧收着
        return map instanceof JsonValues.Folded f ? new JsonValues.Folded(out, fields(f.folded(), fields, wrap)) : out;
    }

    /** 几种之一里这一种对不对得上这个值:列表对列表,表对表(类的必有字段都在),字符串、数、布尔对各自的。 */
    private boolean fits(Object value, ScriptType type) {
        return switch (type) {
            case ScriptType.Named n -> {
                ScriptType.Class c = classes.get(n.name());
                if (c != null && c.items() != null) {
                    yield value instanceof java.util.List<?>;
                }
                yield value instanceof Map<?, ?> map && (c == null || c.fields().stream()
                        .allMatch(f -> f.optional() || map.containsKey(f.name())));
            }
            case ScriptType.ListOf l -> value instanceof java.util.List<?>;
            case ScriptType.Table t -> value instanceof Map<?, ?>;
            case ScriptType.Union u -> u.options().stream().anyMatch(o -> fits(value, o));
            case ScriptType.Choice c -> value instanceof String;
            case ScriptType.Simple s -> switch (s.name()) {
                case "integer", "number" -> value instanceof Number;
                case "string" -> value instanceof String;
                case "boolean" -> value instanceof Boolean;
                default -> true;
            };
        };
    }

    /** 这个动词;没有是 null。 */
    public Verb verb(String group, String verb) {
        Map<String, Verb> verbs = groups.get(group);
        return verbs == null ? null : verbs.get(verb);
    }
}
