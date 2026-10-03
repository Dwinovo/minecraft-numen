package com.dwinovo.numen.agent.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 回执与参数里的 JSON 换成脚本收的 Java 值,换法只在这里:对象成名字到值的表、数组成列表、整数成 Long、null 成 nil。 */
public final class JsonValues {

    private JsonValues() {}

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
        ((JsonObject) e).entrySet().forEach(entry -> map.put(entry.getKey(), toJava(entry.getValue())));
        return map;
    }
}
