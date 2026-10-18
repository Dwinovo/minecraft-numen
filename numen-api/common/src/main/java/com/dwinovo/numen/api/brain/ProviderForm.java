package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.catalog.ModelInfo;
import com.dwinovo.numen.api.agent.catalog.ProviderInfo;
import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.llm.ConnectionProfile;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.llm.NumenLlmClient;
import com.dwinovo.numen.api.agent.llm.ModelCatalog;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.provider.ThinkingPlan;
import com.dwinovo.numen.api.keys.Keys;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 模型档案表单在服务端的那一半:型号目录、草稿解析成端点、连通性检测都只在这里算,界面只画答案。
 * 目录(随包快照加服务器上每天拉一次的缓存)、端点解析(厂家缺省地址、型号能力、思考计划)、发出去的 ping 用的是同一个
 * {@link ModelCatalog}——和同伴开口时走的是同一条路,所以表单上看到的"会怎样"就是真会怎样。
 *
 * <p>草稿的样子与一条档案的内容相同({@link ProviderLibrary#profileFromJson}),界面写、这里读,编解码只那一处。
 * 连通性检测用的 key:草稿里带了 {@code key}(表单上正输入、还没保存的)就用它,否则用这位玩家钥匙串里这个服务的(先他的、再服主的)。
 */
final class ProviderForm {

    static final String STATE_CHECKING = "checking";
    static final String STATE_OK = "ok";
    static final String STATE_NEED_KEY = "need_key";
    static final String STATE_NEED_URL = "need_url";
    static final String STATE_FAILED = "failed";

    private ProviderForm() {}

    /**
     * 一次询问的答案:全部厂家(常用的在前),请求带 {@code models} 时这个厂家的全部型号,带 {@code draft} 时这份草稿
     * 解析出的端点。
     *
     * @param body {@code {"draft": {…}, "models": true}},两项都可缺
     */
    static JsonObject answer(ModelCatalog catalog, JsonObject body) {
        JsonObject out = new JsonObject();
        Set<String> popular = new HashSet<>();
        JsonArray vendors = new JsonArray();
        for (ProviderInfo p : catalog.catalog().popular()) {
            popular.add(p.id());
            vendors.add(vendor(p, true));
        }
        for (ProviderInfo p : catalog.catalog().providers()) {
            if (!popular.contains(p.id())) {
                vendors.add(vendor(p, false));
            }
        }
        out.add("vendors", vendors);
        if (body.has("draft") && body.get("draft").isJsonObject()) {
            ConnectionProfile profile = draft(body);
            if (body.has("models") && body.get("models").getAsBoolean()) {
                ProviderInfo vendor = catalog.catalog().provider(profile.provider());
                JsonArray models = new JsonArray();
                if (vendor != null) {
                    vendor.models().forEach(m -> models.add(model(m)));
                }
                out.add("models", models);
                out.addProperty("modelsFor", profile.provider() == null ? "" : profile.provider());
            }
            out.add("preview", preview(catalog, profile));
        }
        return out;
    }

    /**
     * 检测草稿当前值:存不存都能先试连。缺 key、缺地址当场说;连得通连不通是一次真的请求(思考不发,只关心连得通、密钥对不对)
     * 的结局。
     *
     * @return 结局 {@code {"state": …}};失败时带 {@code cause}(界面按语言翻译)
     */
    static CompletableFuture<JsonObject> check(ModelCatalog catalog, Keys keys, JsonObject body) {
        ConnectionProfile profile = draft(body);
        String typed = body.get("key") instanceof com.google.gson.JsonPrimitive key ? key.getAsString() : "";
        Keys.Found stored = keys.find(profile.provider());
        String key = !typed.isBlank() ? typed : stored == null ? "" : stored.key();
        if (key.isBlank()) {
            return CompletableFuture.completedFuture(state(STATE_NEED_KEY));
        }
        LlmEndpoint endpoint = catalog.resolve(profile.withApiKey(key).withReasoningEffort("auto"));
        if (!endpoint.hasUrl()) {
            return CompletableFuture.completedFuture(state(STATE_NEED_URL));
        }
        return NumenLlmClient.forEndpoint(endpoint)
                .chatStreaming(List.of(new ConvoState.Msg.User("ping")), List.of(), "", null, new CancelToken(), null)
                .handle((result, error) -> {
                    if (error == null) {
                        return state(STATE_OK);
                    }
                    JsonObject failed = state(STATE_FAILED);
                    failed.add("cause", CompanionState.cause(Cause.fromError(error)));
                    return failed;
                });
    }

    static JsonObject state(String state) {
        JsonObject o = new JsonObject();
        o.addProperty("state", state);
        return o;
    }

    private static ConnectionProfile draft(JsonObject body) {
        return ProviderLibrary.profileFromJson(body.getAsJsonObject("draft"));
    }

    private static JsonObject vendor(ProviderInfo p, boolean popular) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.id());
        o.addProperty("name", p.name());
        o.addProperty("host", p.host());
        o.addProperty("popular", popular);
        o.addProperty("defaultModel", defaultModel(p));
        return o;
    }

    /** 换到这个厂家时先选哪个型号:第一个能调工具的,没有就第一个;厂家没有型号是空串。 */
    private static String defaultModel(ProviderInfo vendor) {
        for (ModelInfo m : vendor.models()) {
            if (m.toolCall()) {
                return m.id();
            }
        }
        return vendor.models().isEmpty() ? "" : vendor.models().get(0).id();
    }

    private static JsonObject model(ModelInfo m) {
        JsonObject o = new JsonObject();
        o.addProperty("id", m.id());
        o.addProperty("name", m.name());
        o.addProperty("contextWindow", m.contextWindow());
        o.addProperty("maxOutput", m.maxOutput());
        JsonArray inputs = new JsonArray();
        m.inputs().forEach(inputs::add);
        o.add("inputs", inputs);
        o.addProperty("toolCall", m.toolCall());
        // 能控制思考的型号:档位表里除了自动还有别的
        o.addProperty("reasons", m.thinking().levels().size() > 1);
        o.addProperty("releaseDate", m.releaseDate());
        return o;
    }

    /** 草稿现在对应的端点,不含地址覆盖:目录给的缺省地址、型号能力、思考计划(每档实际会发什么)。 */
    private static JsonObject preview(ModelCatalog catalog, ConnectionProfile profile) {
        LlmEndpoint endpoint = catalog.resolve(profile.withBaseUrl(""));
        ThinkingPlan plan = endpoint.thinkingPlan();
        JsonObject o = new JsonObject();
        o.addProperty("baseUrl", endpoint.baseUrl());
        o.addProperty("contextWindow", endpoint.contextWindow());
        JsonArray steps = new JsonArray();
        for (ThinkingPlan.Step step : plan.steps()) {
            JsonObject s = new JsonObject();
            s.addProperty("level", step.level().id());
            s.addProperty("wire", step.wire().kind().name());
            if (step.wire().effort() != null) {
                s.addProperty("effort", step.wire().effort());
            }
            s.addProperty("tokens", step.wire().tokens());
            steps.add(s);
        }
        o.add("steps", steps);
        o.addProperty("effective", plan.effective(ThinkingLevel.byId(profile.reasoningEffort())).id());
        return o;
    }
}
