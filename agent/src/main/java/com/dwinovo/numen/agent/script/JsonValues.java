package com.dwinovo.numen.agent.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回执与参数里的 JSON 换成脚本收的 Java 值,换法只在这里:对象成名字到值的表、数组成列表、整数成 Long、null 成 nil。
 *
 * <p>对象里键为 {@link #FOLDED} 的那一项是收起来的字段:换成 {@link Folded},作为表它只有自己的字段,收起来的另放着——脚本读得到,
 * 印出来不带(一条路的每一步这样大而少用的字段)。
 */
public final class JsonValues {

    /** 对象里收起来的那几个字段放在这个键下。 */
    public static final String FOLDED = "__folded";

    private JsonValues() {}

    /** 一张收着几个字段的表:作为 {@link Map} 只有自己的字段,收起来的在 {@link #folded}。 */
    public static final class Folded extends java.util.AbstractMap<String, Object> {

        private final Map<String, Object> shown;
        private final Map<String, Object> folded;

        public Folded(Map<String, Object> shown, Map<String, Object> folded) {
            this.shown = shown;
            this.folded = folded;
        }

        public Map<String, Object> folded() {
            return folded;
        }

        @Override
        public java.util.Set<Entry<String, Object>> entrySet() {
            return shown.entrySet();
        }
    }

    /**
     * 一个脚本的值写成 JSON,{@link #toJava} 读回来是同一个值:表是对象、列表是数组、nil 是 JSON null;{@link Folded} 收起来的字段放进
     * {@link #FOLDED} 下。
     *
     * @throws IllegalArgumentException 不是脚本的值(Java 对象、函数)
     */
    public static JsonElement toJson(Object value) {
        return switch (value) {
            case null -> com.google.gson.JsonNull.INSTANCE;
            case String s -> new JsonPrimitive(s);
            case Boolean b -> new JsonPrimitive(b);
            case Number n -> new JsonPrimitive(n);
            case List<?> list -> {
                JsonArray array = new JsonArray();
                list.forEach(v -> array.add(toJson(v)));
                yield array;
            }
            case Folded folded -> {
                JsonObject o = (JsonObject) toJson(new LinkedHashMap<>(folded));
                o.add(FOLDED, toJson(folded.folded()));
                yield o;
            }
            case Map<?, ?> map -> {
                JsonObject o = new JsonObject();
                map.forEach((k, v) -> o.add(String.valueOf(k), toJson(v)));
                yield o;
            }
            default -> throw new IllegalArgumentException("not a script value: " + value.getClass().getName());
        };
    }

    public static Object toJava(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e instanceof JsonPrimitive p) {
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isNumber()) {
                double d = p.getAsDouble();
                return d == Math.rint(d) && Math.abs(d) < 9.0e15 ? (Object) (long) d : (Object) d;
            }
            return p.getAsString();
        }
        if (e instanceof JsonArray a) {
            List<Object> list = new ArrayList<>(a.size());
            a.forEach(item -> list.add(toJava(item)));
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> folded = null;
        for (Map.Entry<String, JsonElement> entry : ((JsonObject) e).entrySet()) {
            if (entry.getKey().equals(FOLDED) && entry.getValue() instanceof JsonObject inner) {
                folded = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> f : inner.entrySet()) {
                    folded.put(f.getKey(), toJava(f.getValue()));
                }
            } else {
                map.put(entry.getKey(), toJava(entry.getValue()));
            }
        }
        return folded == null ? map : new Folded(map, folded);
    }
}
