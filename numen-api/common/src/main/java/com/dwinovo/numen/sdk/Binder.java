package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptEngine;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 从签名推出一个函数({@link ApiFunction}):一个类里每个 {@link Fn} 方法一个。签名就是契约——名字、在哪执行、怎么交回、参数、返回
 * 类型、说明全从方法与它的参数 record 读出来,不另写一份。
 *
 * <h2>只拦会破坏系统的</h2>
 * 绑定不了就抛出({@link IllegalArgumentException},说是哪个函数、哪一条),登记就此失败:
 * <ul>
 *   <li>方法不是 {@code public static},或第一个参数不是 {@link ServerCall}/{@link ClientCall};</li>
 *   <li>第二个参数不是 record,或多于两个参数;</li>
 *   <li>函数名或选项名不是脚本里写得出来的名字(小写字母开头,{@code [a-z0-9_]},不撞语言的关键字与自带全局);</li>
 *   <li>参数或返回值的类型找不到值转换({@link LuaCodecs});</li>
 *   <li>可以不写的位置参数({@link Positional})不在位置参数的最后,或收下余下全部的({@link Rest})不在最后、不是 {@code List};</li>
 *   <li>主人客户端的函数不当场返回({@link Pending}/{@link Job} 只在服务端)。</li>
 * </ul>
 * 风格不拦:没写 {@link Doc}、没写例子、例子读不通、相关函数不存在,都交给 {@link ApiTester#lint} 的报告。
 */
public final class Binder {

    /** 名字空间、组名、函数名、选项名同形:小写字母开头,小写字母、数字、下划线。 */
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,47}");

    private Binder() {}

    /**
     * 这个类里的函数,按方法名排(和类里写的先后无关,帮助字节稳定)。
     *
     * @throws IllegalArgumentException 有一个绑定不了(见类注释),或一个 {@code @Fn} 方法都没有
     */
    public static List<ApiFunction> bind(String namespace, String group, Class<?> functions) {
        List<Method> methods = Arrays.stream(functions.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Fn.class))
                .sorted(Comparator.comparing(Binder::nameOf))
                .toList();
        if (methods.isEmpty()) {
            throw new IllegalArgumentException(functions.getName() + " has no @Fn method for " + namespace + "." + group);
        }
        List<ApiFunction> out = new ArrayList<>();
        for (Method m : methods) {
            out.add(bind(namespace, group, m));
        }
        return out;
    }

    private static String nameOf(Method m) {
        String declared = m.getAnnotation(Fn.class).name();
        return declared.isEmpty() ? LuaCodecs.snake(m.getName()) : declared;
    }

    /** 一个名字在脚本里写不写得出来;写不出来是那句话,能写是 null。 */
    static String badName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return "'" + name + "' is not a name a script can write (a lowercase letter, then [a-z0-9_])";
        }
        if (!ScriptEngine.IN_USE.isKey(name) || !ScriptEngine.IN_USE.functionName(name).equals(name)) {
            return "'" + name + "' is a name " + ScriptEngine.IN_USE.language() + " already uses";
        }
        return null;
    }

    private static ApiFunction bind(String namespace, String group, Method m) {
        String name = nameOf(m);
        String where = namespace + "." + group + "." + name + " (" + m.getDeclaringClass().getSimpleName() + "."
                + m.getName() + ")";
        String bad = badName(name);
        if (bad != null) {
            throw new IllegalArgumentException(where + ": " + bad);
        }
        if (!Modifier.isStatic(m.getModifiers()) || !Modifier.isPublic(m.getModifiers())
                || !Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
            throw new IllegalArgumentException(where + ": an @Fn method is public static, in a public class");
        }
        Class<?>[] types = m.getParameterTypes();
        if (types.length == 0 || types.length > 2 || (types[0] != ServerCall.class && types[0] != ClientCall.class)) {
            throw new IllegalArgumentException(where + ": an @Fn method takes (ServerCall call) or (ClientCall call), "
                    + "then optionally one record of arguments");
        }
        ApiFunction.Side side = types[0] == ServerCall.class ? ApiFunction.Side.SERVER : ApiFunction.Side.CLIENT;
        Constructor<? extends Record> args = null;
        List<ApiFunction.Param> params = List.of();
        if (types.length == 2) {
            if (!types[1].isRecord()) {
                throw new IllegalArgumentException(where + ": its arguments are a record, not " + types[1].getName());
            }
            @SuppressWarnings("unchecked")
            Class<? extends Record> record = (Class<? extends Record>) types[1];
            params = params(where, namespace, record);
            args = constructor(where, record);
        }
        Type returned = m.getGenericReturnType();
        ScriptCatalog.Kind kind = ScriptCatalog.Kind.VALUE;
        Type value = returned;
        if (returned instanceof ParameterizedType p && (p.getRawType() == Pending.class || p.getRawType() == Job.class)) {
            kind = p.getRawType() == Pending.class ? ScriptCatalog.Kind.PENDING : ScriptCatalog.Kind.JOB;
            value = p.getActualTypeArguments()[0];
        } else if (returned == Pending.class || returned == Job.class) {
            throw new IllegalArgumentException(where + ": say what it returns, Pending<R> or Job<R>");
        }
        if (side == ApiFunction.Side.CLIENT && kind != ScriptCatalog.Kind.VALUE) {
            throw new IllegalArgumentException(where + ": a function on the owner's client answers at once; Pending "
                    + "and Job are for the server");
        }
        Codec<Object> returns;
        try {
            returns = codec(value, namespace);
        } catch (IllegalArgumentException none) {
            throw new IllegalArgumentException(where + ": what it returns: " + none.getMessage());
        }
        m.setAccessible(true);
        return new ApiFunction(namespace, group, name, m.getAnnotation(Fn.class).value(), side, kind, params, args,
                returns, Arrays.stream(m.getAnnotationsByType(Example.class)).map(Example::value).toList(),
                Arrays.stream(m.getAnnotationsByType(Note.class)).map(Note::value).toList(),
                m.isAnnotationPresent(SeeAlso.class) ? List.of(m.getAnnotation(SeeAlso.class).value()) : List.of(),
                m);
    }

    /** 参数 record 的组件读成参数,并查位置参数的规矩。 */
    private static List<ApiFunction.Param> params(String where, String namespace, Class<? extends Record> record) {
        List<ApiFunction.Param> out = new ArrayList<>();
        boolean lastPositional = false;
        for (RecordComponent c : record.getRecordComponents()) {
            String name = LuaCodecs.snake(c.getName());
            String bad = badName(name);
            if (bad != null) {
                throw new IllegalArgumentException(where + ": argument " + bad);
            }
            Type t = c.getGenericType();
            boolean optional = t instanceof ParameterizedType p && p.getRawType() == Optional.class;
            Type inner = optional ? ((ParameterizedType) t).getActualTypeArguments()[0] : t;
            ApiFunction.Role role;
            if (c.isAnnotationPresent(Rest.class)) {
                if (optional || !(inner instanceof ParameterizedType p && p.getRawType() == List.class)) {
                    throw new IllegalArgumentException(where + ": the @Rest argument " + name + " is a List (not "
                            + "Optional)");
                }
                inner = ((ParameterizedType) inner).getActualTypeArguments()[0];
                role = ApiFunction.Role.REST;
            } else if (c.isAnnotationPresent(Positional.class)) {
                if (!optional) {
                    throw new IllegalArgumentException(where + ": @Positional is for an Optional argument that may be "
                            + "left out at the end; " + name + " is not Optional");
                }
                role = ApiFunction.Role.OPTIONAL;
            } else {
                role = optional ? ApiFunction.Role.OPTION : ApiFunction.Role.REQUIRED;
            }
            if (role != ApiFunction.Role.OPTION && lastPositional) {
                throw new IllegalArgumentException(where + ": " + name + " comes after the argument that may be left out "
                        + "or takes the rest; that one is the last object");
            }
            lastPositional |= role == ApiFunction.Role.OPTIONAL || role == ApiFunction.Role.REST;
            Codec<Object> codec;
            try {
                codec = codec(inner, namespace);
            } catch (IllegalArgumentException none) {
                throw new IllegalArgumentException(where + ": argument " + name + ": " + none.getMessage());
            }
            Doc doc = c.getAnnotation(Doc.class);
            Omitted omitted = c.getAnnotation(Omitted.class);
            out.add(new ApiFunction.Param(name, role, codec, doc == null ? null : doc.value(),
                    omitted == null ? null : omitted.value()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Codec<Object> codec(Type type, String namespace) {
        return (Codec<Object>) LuaCodecs.of(type, namespace);
    }

    private static Constructor<? extends Record> constructor(String where, Class<? extends Record> record) {
        Class<?>[] types = Arrays.stream(record.getRecordComponents()).map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        try {
            Constructor<? extends Record> c = record.getDeclaredConstructor(types);
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(where + ": its arguments record has no canonical constructor", e);
        }
    }
}
