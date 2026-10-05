package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.JsonValues;
import com.dwinovo.numen.agent.script.ScriptType;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一个 record 是一张表:组件名从驼峰换成下划线就是字段名,{@code Optional} 的组件可以没有;{@link Doc} 是字段与类的说明,{@link Folded}
 * 的字段收起来,{@link Flatten} 的第一个组件摊进这张表并成为父类,{@link Methods} 说值带的方法写在哪个模块里。类声明在 {@link LuaCodecs}。
 *
 * @param <R> 这个 record
 */
final class RecordCodec<R extends Record> implements Codec<R> {

    /** 一个组件:它的字段名、值转换、可不可以没有、收不收起来、摊不摊开。 */
    private record Field(RecordComponent component, String name, Codec<Object> codec, boolean optional,
                         boolean folded, boolean flatten, String doc) {}

    private final Class<R> type;
    private final ScriptType.Class declared;
    private final List<Field> fields = new ArrayList<>();
    private final Constructor<R> constructor;

    @SuppressWarnings("unchecked")
    RecordCodec(Class<R> type, String name) {
        this.type = type;
        String namespace = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "numen";
        RecordComponent[] components = type.getRecordComponents();
        List<ScriptType.Field> declaredFields = new ArrayList<>();
        String parent = null;
        for (int i = 0; i < components.length; i++) {
            RecordComponent c = components[i];
            boolean flatten = c.isAnnotationPresent(Flatten.class);
            Type t = c.getGenericType();
            boolean optional = t instanceof ParameterizedType p && p.getRawType() == Optional.class;
            Type inner = optional ? ((ParameterizedType) t).getActualTypeArguments()[0] : t;
            Codec<Object> codec = (Codec<Object>) LuaCodecs.of(inner, namespace);
            Doc doc = c.getAnnotation(Doc.class);
            if (flatten) {
                if (i != 0 || optional || !(codec instanceof RecordCodec<?> up)) {
                    throw new IllegalArgumentException(type.getName() + "." + c.getName() + ": only the first component "
                            + "may be @Flatten, and it must be a record");
                }
                parent = up.declared.name();
            }
            Field f = new Field(c, LuaCodecs.snake(c.getName()), codec, optional, c.isAnnotationPresent(Folded.class),
                    flatten, doc == null ? null : doc.value());
            fields.add(f);
            if (!flatten) {
                declaredFields.add(optional ? ScriptType.optional(f.name, codec.type(), f.doc)
                        : ScriptType.field(f.name, codec.type(), f.doc));
            }
        }
        Doc classDoc = type.getAnnotation(Doc.class);
        Methods methods = type.getAnnotation(Methods.class);
        ScriptType.Class c = new ScriptType.Class(name, classDoc == null ? null : classDoc.value(), parent,
                declaredFields);
        this.declared = methods == null ? c : c.methodsIn(methods.value());
        LuaCodecs.declare(declared, type);
        try {
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
            }
            this.constructor = type.getDeclaredConstructor(types);
            this.constructor.setAccessible(true);
        } catch (NoSuchMethodException | RuntimeException e) {
            throw new IllegalArgumentException(type.getName() + ": its canonical constructor cannot be used: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public ScriptType type() {
        return declared.type();
    }

    @Override
    public List<ScriptType.Class> classes() {
        return List.of(declared);
    }

    @Override
    public R decode(Object value) {
        Map<String, Object> table = LuaCodecs.table(value);
        if (table == null) {
            throw new BadValue("expected a " + declared.name() + " table; got " + BadValue.given(value));
        }
        List<String> known = new ArrayList<>();
        Object[] args = new Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            if (f.flatten) {
                args[i] = f.codec.decode(table);
                RecordCodec<?> up = (RecordCodec<?>) f.codec;
                up.names(known);
                continue;
            }
            known.add(f.name);
            Object given = table.get(f.name);
            if (given == null) {
                if (!f.optional) {
                    throw new BadValue("field '" + f.name + "' is missing");
                }
                args[i] = Optional.empty();
                continue;
            }
            Object read;
            try {
                read = f.codec.decode(given);
            } catch (BadValue bad) {
                throw bad.in("field '" + f.name + "'");
            }
            args[i] = f.optional ? Optional.of(read) : read;
        }
        for (String key : table.keySet()) {
            if (!known.contains(key)) {
                throw new BadValue("unknown field '" + key + "'; a " + declared.name() + " has: "
                        + String.join(", ", known));
            }
        }
        return construct(args);
    }

    /** 这个类(连同摊进来的)认的字段名。 */
    private void names(List<String> into) {
        for (Field f : fields) {
            if (f.flatten) {
                ((RecordCodec<?>) f.codec).names(into);
            } else {
                into.add(f.name);
            }
        }
    }

    @Override
    public Object encode(R value) {
        Map<String, Object> shown = new LinkedHashMap<>();
        Map<String, Object> folded = new LinkedHashMap<>();
        for (Field f : fields) {
            Object v = read(f.component, value);
            if (f.flatten) {
                if (f.codec.encode(v) instanceof Map<?, ?> up) {
                    up.forEach((k, x) -> shown.put(String.valueOf(k), x));
                }
                continue;
            }
            if (f.optional) {
                if (!(v instanceof Optional<?> o)) {
                    throw new IllegalStateException(type.getName() + "." + f.component.getName() + " is null; an "
                            + "Optional field is Optional.empty() when it has nothing");
                }
                if (o.isEmpty()) {
                    continue;
                }
                v = o.get();
            } else if (v == null) {
                throw new IllegalStateException(type.getName() + "." + f.component.getName() + " is null; make it "
                        + "Optional if it may have nothing");
            }
            (f.folded ? folded : shown).put(f.name, f.codec.encode(v));
        }
        return folded.isEmpty() ? shown : new JsonValues.Folded(shown, folded);
    }

    private static Object read(RecordComponent component, Record value) {
        try {
            var accessor = component.getAccessor();
            accessor.setAccessible(true);
            return accessor.invoke(value);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("cannot read " + component, e);
        }
    }

    private R construct(Object[] args) {
        try {
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof BadValue bad) {
                throw bad;
            }
            if (e.getCause() instanceof IllegalArgumentException wrong) {
                throw new BadValue(wrong.getMessage());
            }
            throw new IllegalStateException("cannot make a " + type.getName(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot make a " + type.getName(), e);
        }
    }
}
