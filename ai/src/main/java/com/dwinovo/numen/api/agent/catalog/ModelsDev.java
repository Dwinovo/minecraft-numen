package com.dwinovo.numen.api.agent.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * models.dev 的数据格式:{@code {厂家id: {id,name,npm,api,doc,models:{型号id:{…}}}}}。
 * 这个类只懂这个格式的两个方向——把文本读成 {@link ProviderInfo}({@link #parse}),
 * 把完整的 {@code api.json} 裁成我们用到的那几个字段({@link #slim})。
 * 随包快照和运行时缓存存的都是裁过的文本,与完整数据同形,所以读它们走同一个 {@link #parse}。
 *
 * <p>已弃用的型号({@code status: deprecated})在这里读进来时就丢掉,目录里没有它,谁也不必再过滤;
 * 档案里还用着这样的型号,就和任何目录里没有的型号一样当作手填的型号。
 */
public final class ModelsDev {

    private ModelsDev() {}

    /** 读出全部厂家,保持文本里的顺序。格式不对抛 {@link RuntimeException}。 */
    public static Map<String, ProviderInfo> parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, ProviderInfo> out = new LinkedHashMap<>();
        for (var e : root.entrySet()) {
            out.put(e.getKey(), provider(e.getKey(), e.getValue().getAsJsonObject()));
        }
        return out;
    }

    private static ProviderInfo provider(String id, JsonObject p) {
        String npm = str(p, "npm");
        Api api = Api.ofNpm(npm);
        String baseUrl = p.has("api") ? str(p, "api") : VendorDefaults.BASE_URLS.getOrDefault(id, "");
        List<ModelInfo> models = new ArrayList<>();
        if (p.has("models") && p.get("models").isJsonObject()) {
            for (var m : p.getAsJsonObject("models").entrySet()) {
                if ("deprecated".equals(str(m.getValue().getAsJsonObject(), "status"))) continue;
                models.add(model(m.getKey(), m.getValue().getAsJsonObject(), api, baseUrl));
            }
        }
        return new ProviderInfo(id, p.has("name") ? str(p, "name") : id, baseUrl, api, str(p, "doc"),
                VendorDefaults.HEADERS.getOrDefault(id, Map.of()), models);
    }

    private static ModelInfo model(String id, JsonObject m, Api vendorApi, String vendorBaseUrl) {
        // 网关类厂家里个别型号自己指定了协议和地址,以型号为准。
        JsonObject route = m.has("provider") && m.get("provider").isJsonObject()
                ? m.getAsJsonObject("provider") : new JsonObject();
        Api api = route.has("npm") ? Api.ofNpm(str(route, "npm")) : vendorApi;
        String baseUrl = route.has("api") ? str(route, "api") : vendorBaseUrl;

        JsonObject limit = obj(m, "limit");
        List<String> inputs = new ArrayList<>();
        JsonObject modalities = obj(m, "modalities");
        if (modalities.has("input") && modalities.get("input").isJsonArray()) {
            for (JsonElement el : modalities.getAsJsonArray("input")) {
                if (el.isJsonPrimitive() && !"text".equals(el.getAsString())) inputs.add(el.getAsString());
            }
        }
        String interleaved = null;
        if (m.has("interleaved") && m.get("interleaved").isJsonObject()) {
            String field = str(m.getAsJsonObject("interleaved"), "field");
            interleaved = field.isEmpty() ? null : field;
        }
        ModelInfo.Cost cost = null;
        if (m.has("cost") && m.get("cost").isJsonObject()) {
            JsonObject c = m.getAsJsonObject("cost");
            cost = new ModelInfo.Cost(num(c, "input"), num(c, "output"),
                    num(c, "cache_read"), num(c, "cache_write"));
        }
        JsonArray options = m.has("reasoning_options") && m.get("reasoning_options").isJsonArray()
                ? m.getAsJsonArray("reasoning_options") : null;
        return new ModelInfo(id, m.has("name") ? str(m, "name") : id,
                (int) num(limit, "context"), (int) num(limit, "output"), inputs,
                bool(m, "tool_call", false), bool(m, "temperature", true),
                ThinkingTable.parse(bool(m, "reasoning", false), options, (int) num(limit, "output")),
                interleaved, cost, api, baseUrl, str(m, "release_date"));
    }

    /**
     * 把完整的 {@code api.json} 裁成只含我们用到的字段的文本:一个厂家一行,按厂家 id 排序,
     * 这样随包快照每次更新的差异只落在真正变了的厂家上。
     */
    public static String slim(String fullJson) {
        JsonObject root = JsonParser.parseString(fullJson).getAsJsonObject();
        Map<String, JsonObject> sorted = new TreeMap<>();
        for (var e : root.entrySet()) {
            sorted.put(e.getKey(), slimProvider(e.getKey(), e.getValue().getAsJsonObject()));
        }
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (var e : sorted.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append(new JsonPrimitive(e.getKey())).append(':').append(e.getValue());
        }
        return sb.append("\n}\n").toString();
    }

    private static JsonObject slimProvider(String id, JsonObject p) {
        JsonObject out = new JsonObject();
        copy(p, out, "name", "npm", "api", "doc");
        JsonObject models = new JsonObject();
        if (p.has("models") && p.get("models").isJsonObject()) {
            for (var e : p.getAsJsonObject("models").entrySet()) {
                models.add(e.getKey(), slimModel(e.getValue().getAsJsonObject()));
            }
        }
        out.add("models", models);
        return out;
    }

    private static JsonObject slimModel(JsonObject m) {
        JsonObject out = new JsonObject();
        copy(m, out, "name", "status", "release_date", "reasoning", "tool_call", "temperature",
                "reasoning_options", "interleaved");
        JsonObject limit = obj(m, "limit");
        if (limit.size() > 0) {
            JsonObject l = new JsonObject();
            copy(limit, l, "context", "output");
            out.add("limit", l);
        }
        JsonObject modalities = obj(m, "modalities");
        if (modalities.has("input")) {
            JsonObject mo = new JsonObject();
            mo.add("input", modalities.get("input"));
            out.add("modalities", mo);
        }
        JsonObject cost = obj(m, "cost");
        if (cost.size() > 0) {
            JsonObject c = new JsonObject();
            copy(cost, c, "input", "output", "cache_read", "cache_write");
            out.add("cost", c);
        }
        if (m.has("provider") && m.get("provider").isJsonObject()) {
            JsonObject route = new JsonObject();
            copy(m.getAsJsonObject("provider"), route, "npm", "api");
            if (route.size() > 0) out.add("provider", route);
        }
        return out;
    }

    private static void copy(JsonObject from, JsonObject to, String... keys) {
        for (String k : keys) {
            if (from.has(k) && !from.get(k).isJsonNull()) to.add(k, from.get(k));
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static double num(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber()
                ? o.get(key).getAsDouble() : 0;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isBoolean()
                ? o.get(key).getAsBoolean() : fallback;
    }
}
