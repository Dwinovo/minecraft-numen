package com.dwinovo.numen.agent.tool.api;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the LLM-facing JSON parameter schema for a {@link NumenAction} method
 * from its signature, so a tool author never hand-writes one. Each
 * {@link Arg}-annotated parameter becomes a property; non-{@code @Arg}
 * parameters (engine-injected context) are skipped.
 *
 * <h2>Output contract (OpenAI strict / structured-output dialect)</h2>
 * <pre>
 * { "type": "object",
 *   "properties": { &lt;arg&gt;: { "type": ..., "description": ..., [constraints] }, ... },
 *   "required": [ every property name, in declaration order ],
 *   "additionalProperties": false }
 * </pre>
 * <b>Every</b> property is listed in {@code required}; an "optional" argument is
 * expressed as a nullable type union {@code [type, "null"]} (set
 * {@code @Arg(required=false)}), never by omission from {@code required}. This
 * mirrors the hand-written schemas the built-in tools used to carry.
 *
 * <h2>Type mapping</h2>
 * {@code String→string}, {@code int/long→integer}, {@code double/float→number},
 * {@code boolean→boolean}, enums {@code →string}, {@code List<T>/T[]→array} of
 * the mapped element type. Numeric {@code min/max} → {@code minimum/maximum};
 * array {@code minItems} → {@code minItems}; {@code enumValues} → {@code enum}.
 */
public final class ToolSchema {

    private ToolSchema() {}

    /** Tool name: the {@link NumenAction#name()} override, else the method name. */
    public static String actionName(Method method) {
        NumenAction a = method.getAnnotation(NumenAction.class);
        if (a == null) {
            throw new IllegalArgumentException(method + " is not annotated with @NumenAction");
        }
        return a.name().isEmpty() ? method.getName() : a.name();
    }

    /** Build the parameter schema from the method's {@code @Arg} parameters. */
    public static Map<String, Object> schemaFor(Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter p : method.getParameters()) {
            Arg arg = p.getAnnotation(Arg.class);
            if (arg == null) continue;   // engine-injected context — not a model argument
            String name = arg.name().isEmpty() ? p.getName() : arg.name();
            properties.put(name, propertySchema(p, arg));
            if (arg.required()) required.add(name);
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> propertySchema(Parameter p, Arg arg) {
        Class<?> type = p.getType();
        if (type.isArray() || List.class.isAssignableFrom(type)) {
            Class<?> elem = elementType(p, type);
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "array");
            prop.put("description", arg.value());
            // Array of objects (a record element) → recurse into an object schema.
            prop.put("items", elem.isRecord() ? objectSchema(elem) : Map.of("type", jsonType(elem)));
            if (arg.minItems() >= 0) prop.put("minItems", arg.minItems());
            return prop;
        }
        return scalarSchema(type, arg);
    }

    /** Object schema for a record element: one property per {@code @Arg} record component. */
    private static Map<String, Object> objectSchema(Class<?> recordClass) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent rc : recordClass.getRecordComponents()) {
            Arg arg = rc.getAnnotation(Arg.class);
            String name = (arg != null && !arg.name().isEmpty()) ? arg.name() : rc.getName();
            props.put(name, scalarSchema(rc.getType(), arg));
            if (arg == null || arg.required()) required.add(name);
        }
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("type", "object");
        obj.put("properties", props);
        obj.put("required", required);
        obj.put("additionalProperties", false);
        return obj;
    }

    /** Schema for one scalar property/field (handles nullable union, bounds, enum). */
    private static Map<String, Object> scalarSchema(Class<?> type, Arg arg) {
        Map<String, Object> prop = new LinkedHashMap<>();
        String base = jsonType(type);
        boolean nullable = arg != null && arg.nullable();
        prop.put("type", nullable ? List.of(base, "null") : base);
        if (arg != null) {
            prop.put("description", arg.value());
            if (!Double.isNaN(arg.min())) prop.put("minimum", bound(base, arg.min()));
            if (!Double.isNaN(arg.max())) prop.put("maximum", bound(base, arg.max()));
            if (arg.enumValues().length > 0) prop.put("enum", List.of(arg.enumValues()));
        }
        return prop;
    }

    /** A numeric bound boxed to match the property's JSON type: Integer for {@code integer}, Double else. */
    private static Object bound(String base, double v) {
        if (base.equals("integer")) return (int) v;   // autoboxes to Integer, matching int-literal schemas
        return v;                                      // autoboxes to Double
    }

    /** Element type of a {@code List<T>} or {@code T[]} parameter. */
    private static Class<?> elementType(Parameter p, Class<?> type) {
        if (type.isArray()) return type.getComponentType();
        Type generic = p.getParameterizedType();
        if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> elem) {
            return elem;
        }
        return String.class;   // raw List → assume string items
    }

    private static String jsonType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class) return "integer";
        if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type.isEnum()) return "string";
        throw new IllegalArgumentException("unsupported @Arg parameter type: " + type.getName());
    }
}
