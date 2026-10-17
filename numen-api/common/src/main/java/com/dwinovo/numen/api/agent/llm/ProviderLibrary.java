package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.library.JsonLibrary;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.api.agent.catalog.BundledSnapshot;
import com.dwinovo.numen.api.agent.catalog.Catalog;
import com.dwinovo.numen.api.agent.catalog.DownloadCache;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The player's library of named LLM provider configurations, at
 * {@code config/numen/providers.json} — the authoring surface behind the panel's
 * "提供商" tab, mirroring {@link com.dwinovo.numen.api.persona.PersonaLibrary}'s shape.
 * A companion selects one entry at summon time (or later); its requests then run
 * against that entry's backend, so different companions in the same session can
 * talk to different providers.
 *
 * <p>The library starts EMPTY and every entry is player-created and editable —
 * no seeds, no default entry, no fallback: a companion talks through exactly the
 * entry it selected, and an incomplete entry (e.g. no API key yet) surfaces as a
 * plain error on first use. Client-side singleton.
 *
 * <p>档案只存玩家填的东西({@link ConnectionProfile});能力(上下文、思考档位、协议、缺省地址)
 * 一律经 {@link #resolve} 到型号目录里查,档案、界面、压缩阈值、请求都从那一个解析结果取。
 */
public final class ProviderLibrary extends JsonLibrary<ProviderLibrary.Entry> {

    /** One named provider configuration. May be INCOMPLETE — only the name is
     *  required to save; a missing API key surfaces as a plain error the first time
     *  a companion tries to talk through it (error-driven guidance, no silent
     *  fallback of any kind). */
    public record Entry(String id, String name, ConnectionProfile profile) {}

    /** 型号目录:运行时缓存盖过随包快照。 */
    private final DownloadCache cache;
    private final Catalog catalog;
    private final java.util.function.Supplier<String> proxy;

    public Catalog catalog() {
        return catalog;
    }

    /** 客户端启动时调一次:后台刷新 models.dev 缓存(一天最多拉一次,拉不到静默用缓存或快照)。 */
    public void refreshCatalog() {
        cache.refresh(proxy.get());
    }

    private static ProviderLibrary instance;

    /**
     * 测试可直接用临时路径构造;游戏内走 {@link #instance()}。
     *
     * @param catalogCache models.dev 缓存文件({@code config/numen/cache/models-dev.json})
     * @param io           存档用的读写通道:游戏里是服务器那条写车道
     * @param proxy        全局代理 {@code host:port},每次现问:游戏里是服务器配置里此刻的那一个
     */
    public ProviderLibrary(java.nio.file.Path file, java.nio.file.Path catalogCache,
                           com.dwinovo.numen.api.store.Io io, java.util.function.Supplier<String> proxy) {
        super(file, FORMAT, io);
        this.proxy = proxy;
        this.cache = new DownloadCache(catalogCache);
        this.catalog = new Catalog(cache, BundledSnapshot.INSTANCE);
        load();
    }

    public static ProviderLibrary instance() {
        if (instance == null) {
            instance = new ProviderLibrary(configDir().resolve("providers.json"),
                    configDir().resolve("cache").resolve("models-dev.json"),
                    com.dwinovo.numen.api.NumenIo.lanes().io("server"), () -> Services.CONFIG.getProxy());
        }
        return instance;
    }

    /**
     * Resolve an entry id to everything a request needs, verbatim — no fallback of any
     * kind. Unknown/deleted id → an unresolved endpoint, whose missing address and API key
     * produce the same honest errors an unfinished entry does. Proxy is per-profile (国内外站点
     * 常需不同的走线);留空跟随全局设置。
     */
    public LlmEndpoint resolve(String id) {
        Entry e = get(id);
        return e == null ? LlmEndpoint.unresolved(proxy.get()) : resolve(e.profile());
    }

    /** 同一套解析用于还没保存的草稿(设置界面的预览与连通性检测)。 */
    public LlmEndpoint resolve(ConnectionProfile profile) {
        return EndpointResolver.resolve(profile, catalog, proxy.get());
    }

    /** Create an entry — only the name is required; everything else may be blank. */
    public Entry create(String name, ConnectionProfile profile) {
        Entry e = new Entry(freshId("prov"), name, profile);
        putAndSave(e);
        return e;
    }

    public void update(Entry e) {
        if (e == null || !entries.containsKey(e.id())) return;
        putAndSave(e);
    }

    // ---- pending summon assignment (same mechanism as PersonaLibrary.pendSummon:
    // the new companion's UUID is unknown until the roster snapshot arrives) ----

    private static final Map<String, String> PENDING_SUMMON = new LinkedHashMap<>();

    /** Remember the provider entry chosen for a companion being summoned (by name). */
    public static void pendSummon(String name, String entryId) {
        if (name == null || entryId == null) return;
        PENDING_SUMMON.put(name, entryId);
    }

    /** Take (and clear) the entry id pending for a just-arrived companion name, or null. */
    public static String takePendingSummon(String name) {
        return PENDING_SUMMON.remove(name);
    }

    // ---- persistence hooks ----

    @Override
    protected String logTag() {
        return "numen-providers";
    }

    @Override
    protected String idOf(Entry e) {
        return e.id();
    }

    @Override
    protected Entry readEntry(JsonObject o) {
        return entryFromJson(o);
    }

    @Override
    protected JsonObject writeEntry(Entry e) {
        return entryToJson(e);
    }

    /** 一条档案的 JSON(落盘的同一个样子,含 key):管理员经库协议读写它,两头都用这一个编解码。 */
    public static Entry entryFromJson(JsonObject o) {
        return new Entry(strOrNull(o, "id"), strOrNull(o, "name"), new ConnectionProfile(
                strOrNull(o, "provider"), strOrNull(o, "protocol"), strOrNull(o, "model"),
                strOrNull(o, "api_key"), strOrNull(o, "base_url"), strOrNull(o, "proxy"),
                strOrNull(o, "reasoning_effort"),
                o.has("ctx") && o.get("ctx").isJsonPrimitive() ? o.get("ctx").getAsInt() : 0,
                o.has("temperature") && o.get("temperature").isJsonPrimitive()
                        ? o.get("temperature").getAsDouble() : null,
                o.has("max_tokens") && o.get("max_tokens").isJsonPrimitive() ? o.get("max_tokens").getAsInt() : 0,
                o.has("compat") && o.get("compat").isJsonObject() ? o.getAsJsonObject("compat") : null));
    }

    /** {@link #entryFromJson} 的逆。 */
    public static JsonObject entryToJson(Entry e) {
        ConnectionProfile p = e.profile();
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id());
        o.addProperty("name", e.name());
        if (nb(p.provider())) o.addProperty("provider", p.provider());
        if (nb(p.protocol())) o.addProperty("protocol", p.protocol());
        if (nb(p.model())) o.addProperty("model", p.model());
        if (nb(p.apiKey())) o.addProperty("api_key", p.apiKey());
        if (nb(p.baseUrl())) o.addProperty("base_url", p.baseUrl());
        if (nb(p.reasoningEffort())) o.addProperty("reasoning_effort", p.reasoningEffort());
        if (nb(p.proxy())) o.addProperty("proxy", p.proxy());
        if (p.ctx() > 0) o.addProperty("ctx", p.ctx());
        if (p.temperature() != null) o.addProperty("temperature", p.temperature());
        if (p.maxTokens() > 0) o.addProperty("max_tokens", p.maxTokens());
        if (p.compat() != null && p.compat().size() > 0) o.add("compat", p.compat());
        return o;
    }

    // ---- 版本 ----

    /**
     * 第 1 版:厂家 id 是各家自己的叫法(含玩家顺手填的别名)。第 2 版起是 models.dev 的 id——
     * 版本号就是"迁过没有"的判据:{@code lmstudio} 既是老 id(要变成自定义厂家)也是 models.dev 的 id
     * (新档案可能选了它),只看 id 分不出来。
     */
    private static final JsonDoc.Format FORMAT = JsonDoc.Format.of(2).upgrade(1, ProviderLibrary::toModelsDevIds);

    /** 第 2 版之前的库在根上记过的"已迁过"标记;有它的库厂家 id 早已是 models.dev 的 id,只差一个版本号。 */
    private static final String LEGACY_MARKER = "models_dev_ids";

    /** 老厂家 id(含玩家顺手会填的别名)→ models.dev 的厂家 id。只在升级里用。 */
    private static final Map<String, String> LEGACY_PROVIDER_IDS = Map.ofEntries(
            Map.entry("kimi", "moonshotai"), Map.entry("moonshot", "moonshotai"),
            Map.entry("doubao", "volcengine"), Map.entry("ark", "volcengine"),
            Map.entry("qwen", "alibaba-cn"), Map.entry("tongyi", "alibaba-cn"),
            Map.entry("aliyun", "alibaba-cn"), Map.entry("dashscope", "alibaba-cn"),
            Map.entry("glm", "zhipuai"), Map.entry("zhipu", "zhipuai"),
            Map.entry("silicon", "siliconflow-cn"), Map.entry("siliconflow", "siliconflow-cn"),
            Map.entry("gemini", "google"), Map.entry("grok", "xai"),
            Map.entry("openai-compatible", "openai"));

    /** 本地部署的老厂家 id → 它们当年的缺省地址;升级成自定义厂家(OpenAI 兼容协议),地址写进档案。 */
    private static final Map<String, String> LEGACY_LOCAL_PROVIDERS = Map.of(
            "ollama", "http://localhost:11434/v1",
            "lmstudio", "http://localhost:1234/v1",
            "vllm", "http://localhost:8000/v1");

    /**
     * 档案里的老厂家 id 换成 models.dev 的 id;{@code ollama}、{@code lmstudio}、{@code vllm} 变成自定义厂家,
     * 玩家填过的地址不动,别的字段原样保留。
     */
    private static JsonObject toModelsDevIds(JsonObject root) {
        if (root.remove(LEGACY_MARKER) != null || !(root.get("entries") instanceof com.google.gson.JsonArray entries)) {
            return root;
        }
        for (var el : entries) {
            if (!el.isJsonObject() || !(el.getAsJsonObject().get("provider") instanceof com.google.gson.JsonPrimitive)) {
                continue;
            }
            JsonObject entry = el.getAsJsonObject();
            String old = entry.get("provider").getAsString().strip().toLowerCase(java.util.Locale.ROOT);
            if (LEGACY_LOCAL_PROVIDERS.containsKey(old)) {
                entry.addProperty("provider", "custom");
                entry.addProperty("protocol", "chat");
                if (!entry.has("base_url") || entry.get("base_url").getAsString().isBlank()) {
                    entry.addProperty("base_url", LEGACY_LOCAL_PROVIDERS.get(old));
                }
            } else if (LEGACY_PROVIDER_IDS.containsKey(old)) {
                entry.addProperty("provider", LEGACY_PROVIDER_IDS.get(old));
            }
        }
        return root;
    }
}
