package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptType;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一个登记了的 API 函数,由 {@link Binder} 从 {@code @Fn} 方法与它的参数 record 推出,之后不变。登记处、派发、帮助、测试都只读它:
 * 名字、在哪执行、怎么交回、参数(名字、位置还是选项、值转换、说明)、返回类型、例子、注意、相关。
 *
 * <p>参数怎么写:按顺序的对象依次给位置参数({@link Role#REQUIRED},最后可以有一个可以不写的 {@link Role#OPTIONAL} 或收下余下全部的
 * {@link Role#REST}:{@code f(a, b)}、{@code f({a, b})} 一样,一项是扫描交回的一团时展开成它的每一格),写在最后的选项表按名字给选项
 * ({@link Role#OPTION})。
 */
public final class ApiFunction {

    /** 在哪执行。 */
    public enum Side {
        /** 服务端:身体与世界。 */
        SERVER,
        /** 主人客户端:只有那里才有的数据。 */
        CLIENT
    }

    /** 一个参数怎么写。 */
    public enum Role {
        /** 按顺序的对象,必须写。 */
        REQUIRED,
        /** 最后一个按顺序的对象,可以不写。 */
        OPTIONAL,
        /** 最后一个按顺序的对象,收下余下的全部(或只给一张列表)。 */
        REST,
        /** 选项表里的一项,可以不写。 */
        OPTION
    }

    /**
     * 一个参数。
     *
     * @param name    脚本里的名字(下划线)
     * @param codec   它的值转换:{@link Role#REST} 是一项的,可以不写的是里面那个值的
     * @param doc     一句说明;没写是 null
     * @param omitted 不写时会怎样;没写是 null
     */
    public record Param(String name, Role role, Codec<Object> codec, String doc, String omitted) {

        /** 它的类型写在签名里:收下余下全部的写成 {@code T|T[]}。 */
        public ScriptType type() {
            return role == Role.REST ? ScriptType.union(codec.type(), ScriptType.listOf(codec.type())) : codec.type();
        }

        /** 帮助里它的那句话:说明接上不写时会怎样。 */
        public String explained() {
            String said = doc == null ? "" : doc;
            if (omitted != null) {
                said = (said.isEmpty() ? "" : said + " ") + "Omit to " + omitted + ".";
            }
            return said.isEmpty() ? null : said;
        }
    }

    /** 参数读不成:哪一个、错在哪,看得出想写什么时照这一种写法该写成的值。 */
    public static final class BadArgument extends RuntimeException {

        private final transient Param param;
        private final transient Object instead;

        BadArgument(Param param, String message, Object instead) {
            super(message, null, false, false);
            this.param = param;
            this.instead = instead;
        }

        /** 读不成的那一个;说不上是哪一个(多给了对象、写错了选项名)是 null。 */
        public Param param() {
            return param;
        }

        /** 照这一种写法该写成的那个值;看不出是 null。 */
        public Object instead() {
            return instead;
        }
    }

    private final String namespace;
    private final String group;
    private final String name;
    private final String summary;
    private final Side side;
    private final ScriptCatalog.Kind kind;
    private final List<Param> params;
    private final Constructor<? extends Record> args;
    private final Codec<Object> returns;
    private final List<String> examples;
    private final List<String> notes;
    private final List<String> seeAlso;
    private final Method method;

    ApiFunction(String namespace, String group, String name, String summary, Side side, ScriptCatalog.Kind kind,
                List<Param> params, Constructor<? extends Record> args, Codec<Object> returns, List<String> examples,
                List<String> notes, List<String> seeAlso, Method method) {
        this.namespace = namespace;
        this.group = group;
        this.name = name;
        this.summary = summary;
        this.side = side;
        this.kind = kind;
        this.params = List.copyOf(params);
        this.args = args;
        this.returns = returns;
        this.examples = List.copyOf(examples);
        this.notes = List.copyOf(notes);
        this.seeAlso = List.copyOf(seeAlso);
        this.method = method;
    }

    public String namespace() {
        return namespace;
    }

    /** 组名,不带名字空间:{@code work}。 */
    public String group() {
        return group;
    }

    /** 函数名:{@code dig}。 */
    public String name() {
        return name;
    }

    /** 组的全名:{@code numen.work}。 */
    public String groupName() {
        return namespace + "." + group;
    }

    /** 脚本里的全名:{@code numen.work.dig}。 */
    public String fullName() {
        return groupName() + "." + name;
    }

    public String summary() {
        return summary;
    }

    public Side side() {
        return side;
    }

    public ScriptCatalog.Kind kind() {
        return kind;
    }

    public List<Param> params() {
        return params;
    }

    /** 返回值的转换({@code void} 的是不返回值的那一个)。 */
    public Codec<Object> returns() {
        return returns;
    }

    public List<String> examples() {
        return examples;
    }

    public List<String> notes() {
        return notes;
    }

    /** 相关函数的全名。 */
    public List<String> seeAlso() {
        return seeAlso;
    }

    /** 选项名。 */
    public Set<String> options() {
        return params.stream().filter(p -> p.role() == Role.OPTION).map(Param::name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** 按顺序的参数。 */
    public List<Param> positionals() {
        return params.stream().filter(p -> p.role() != Role.OPTION).toList();
    }

    /** 脚本那一侧要知道的:按顺序的对象最多几个、选项名、怎么交回、返回类型与它的样子。 */
    public ScriptCatalog.Function script() {
        List<Param> positionals = positionals();
        boolean rest = !positionals.isEmpty() && positionals.getLast().role() == Role.REST;
        return new ScriptCatalog.Function(rest ? Integer.MAX_VALUE : positionals.size(), options(), kind,
                returns.type(), ScriptType.sample(returns.type(), LuaCodecs::classNamed));
    }

    /**
     * 脚本里写的对象与选项按参数名排好(脚本的值原样):按顺序的对象依次给位置参数,收下余下全部的那一个收下余下的(只给了一张列表就是
     * 它;一张只有数的列表是写错了的一格,算一项);选项按名字。
     *
     * @throws BadArgument 对象多了、选项名不对、把位置参数写进了选项表
     */
    public Map<String, Object> named(List<Object> objects, Map<String, Object> options) {
        List<Param> positionals = positionals();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < positionals.size() && i < objects.size(); i++) {
            Param p = positionals.get(i);
            if (p.role() == Role.REST) {
                List<Object> rest = objects.subList(i, objects.size());
                out.put(p.name(), rest.size() == 1 && rest.getFirst() instanceof List<?> list
                        && Positions.coordinates(list) == null ? list : new ArrayList<>(rest));
                break;
            }
            out.put(p.name(), objects.get(i));
        }
        boolean rest = !positionals.isEmpty() && positionals.getLast().role() == Role.REST;
        if (!rest && objects.size() > positionals.size()) {
            throw new BadArgument(null, "takes " + (positionals.isEmpty() ? "no objects"
                    : positionals.size() + " object(s)") + ", got " + objects.size()
                    + "; a position is one table {x = …, y = …, z = …}", null);
        }
        for (Map.Entry<String, Object> option : options.entrySet()) {
            Param p = params.stream().filter(q -> q.name().equals(option.getKey())).findFirst().orElse(null);
            if (p == null) {
                throw new BadArgument(null, "unknown option '" + option.getKey() + "'; the options are: "
                        + (options().isEmpty() ? "none" : String.join(", ", options())), null);
            }
            if (p.role() != Role.OPTION) {
                throw new BadArgument(p, "'" + p.name() + "' is an object, not an option: give it in order before "
                        + "the options table", null);
            }
            out.put(p.name(), option.getValue());
        }
        return out;
    }

    /**
     * 按参数名排好的脚本的值读成参数 record。nil 与没写同义。
     *
     * @throws BadArgument 缺了必须写的、一个值读不成(说哪一个参数、要什么、给了什么)
     */
    public Record decode(Map<String, Object> named) {
        if (args == null) {
            if (!named.isEmpty()) {
                throw new BadArgument(null, "takes no arguments, got " + String.join(", ", named.keySet()), null);
            }
            return null;
        }
        Object[] values = new Object[params.size()];
        for (int i = 0; i < params.size(); i++) {
            Param p = params.get(i);
            Object given = named.get(p.name());
            if (given == null) {
                if (p.role() == Role.REQUIRED || p.role() == Role.REST) {
                    throw new BadArgument(p, "argument '" + p.name() + "' is missing (or nil)", null);
                }
                values[i] = Optional.empty();
                continue;
            }
            Object read;
            try {
                if (p.role() == Role.REST) {
                    // 一项是扫描交回的一团时,那一团的每一格各是一项
                    List<Object> items = Positions.items(given);
                    List<Object> out = new ArrayList<>(items.size());
                    for (int k = 0; k < items.size(); k++) {
                        try {
                            out.add(p.codec().decode(items.get(k)));
                        } catch (BadValue bad) {
                            throw items.size() == 1 ? bad : bad.in("item " + (k + 1));
                        }
                    }
                    read = List.copyOf(out);
                } else {
                    read = p.codec().decode(given);
                }
            } catch (BadValue bad) {
                throw new BadArgument(p, "argument '" + p.name() + "': " + bad.getMessage(), bad.instead());
            }
            values[i] = p.role() == Role.REQUIRED || p.role() == Role.REST ? read : Optional.of(read);
        }
        try {
            return args.newInstance(values);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IllegalArgumentException wrong) {
                throw new BadArgument(null, wrong.getMessage(), null);
            }
            throw new IllegalStateException(fullName() + ": cannot make its arguments", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(fullName() + ": cannot make its arguments", e);
        }
    }

    /** 读好的参数写回脚本的值,按参数名(没写的可以不写的参数不在里面);{@link #decode} 读回来是同一份。 */
    public Map<String, Object> encode(Record values) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (values == null) {
            return out;
        }
        RecordComponent[] components = values.getClass().getRecordComponents();
        for (int i = 0; i < params.size(); i++) {
            Param p = params.get(i);
            Object v;
            try {
                var accessor = components[i].getAccessor();
                accessor.setAccessible(true);
                v = accessor.invoke(values);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(fullName() + ": cannot read its arguments", e);
            }
            if (v instanceof Optional<?> o) {
                if (o.isEmpty()) {
                    continue;
                }
                v = o.get();
            }
            if (p.role() == Role.REST) {
                List<Object> items = new ArrayList<>();
                ((List<?>) v).forEach(item -> items.add(p.codec().encode(item)));
                out.put(p.name(), items);
            } else {
                out.put(p.name(), p.codec().encode(v));
            }
        }
        return out;
    }

    /** 参数 record 的类型;不收参数是 null。 */
    public Class<? extends Record> argsType() {
        return args == null ? null : args.getDeclaringClass();
    }

    /**
     * 执行它:{@code call} 是这一侧的调用({@link ServerCall} 或 {@link ClientCall}),{@code values} 是读好的参数。
     *
     * @throws ApiError 它说的失败
     */
    Object invoke(Object call, Record values) {
        try {
            return args == null ? method.invoke(null, call) : method.invoke(null, call, values);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException thrown) {
                throw thrown;
            }
            throw new IllegalStateException(fullName() + " threw", e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(fullName() + " cannot be called", e);
        }
    }

    @Override
    public String toString() {
        return fullName();
    }
}
