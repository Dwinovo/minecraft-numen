package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.keys.Keys;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryKind;
import com.dwinovo.numen.api.library.Shelf;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 一位玩家的模型档案库:库的一种({@link #KIND}),一份档案一个 JSON 文件——{@code provider/<命名空间>/<名字>.json}。玩家看到
 * "内置"(服务器目录里,Numen 或插件随包带来的推荐配置,管理员可改)与"我的"两组,同名时我的优先,存储、开关、同步都是
 * {@link Library} 这一套;一位同伴选定其中一条,她的请求就跑在那条档案的后端上。
 *
 * <p>档案<b>不含 key</b>:只写用哪个服务商、哪个型号这些。key 按服务商存在钥匙串里({@link Keys}),用时先找主人的、再找服主的,
 * 所以一条内置档案填上对应服务商的 key 就能用,jar 里永远没有 key。没填 key 的档案在第一次请求时给出一条诚实的错误
 * ({@code Mind.unavailable}),不退到别的档案。
 *
 * <p>档案只存玩家填的东西({@link ConnectionProfile});能力(上下文、思考档位、协议、缺省地址)一律经 {@link ModelCatalog}
 * 解析到型号目录里查,档案、界面、压缩阈值、请求都从那一个解析结果取。
 *
 * <p>线程:服务端主线程。
 */
public final class ProviderLibrary {

    /** 模型档案这种库:一条一个 {@code .json}。 */
    public static final LibraryKind KIND = LibraryKind.files("provider", ".json");

    /** 一份档案;{@code id} 是 {@code 命名空间:名字},{@code name} 是名字。可以不完整:没填 key 的档案在第一次请求时给出诚实的错误。 */
    public record Entry(String id, String name, ConnectionProfile profile) {}

    private final Library library;
    private final Keys keys;
    private final ModelCatalog catalog;

    public ProviderLibrary(Library library, Keys keys, ModelCatalog catalog) {
        this.library = library;
        this.keys = keys;
        this.catalog = catalog;
    }

    public ModelCatalog catalog() {
        return catalog;
    }

    /** 这位玩家的 key 的出处。 */
    public Keys keys() {
        return keys;
    }

    /** 此刻在用的档案(没关掉的),内置在前我的在后。 */
    public List<Entry> list() {
        return library.active().stream().map(ProviderLibrary::entry).filter(java.util.Objects::nonNull).toList();
    }

    /** 叫这个 id、此刻在用的档案;没有或被关掉是 {@code null}。 */
    public Entry get(String id) {
        Library.Entry entry = library.find(EntryId.parse(id));
        return entry == null ? null : entry(entry);
    }

    /** 这份档案用的 key 与它的出处;档案不在或没填 key 是 {@code null}。 */
    public Keys.Found key(String id) {
        Entry entry = get(id);
        return entry == null ? null : keys.find(entry.profile().provider());
    }

    /**
     * 把档案解析成发请求要的全部,原样,不兜底:没有这个 id → 缺地址缺 key 的端点,和没填完的档案一样给出诚实的错误。
     * 代理是逐档案的(国内外站点常需不同的走线);留空跟随全局设置。
     */
    public LlmEndpoint resolve(String id) {
        Entry entry = get(id);
        if (entry == null) {
            return catalog.unresolved();
        }
        Keys.Found found = keys.find(entry.profile().provider());
        return catalog.resolve(entry.profile().withApiKey(found == null ? null : found.key()));
    }

    /** 新建我的档案:只有名字必填,其余可以空。重名自动加 _2 后缀。 */
    public Entry create(String name, ConnectionProfile profile) {
        EntryId id = library.mine().unique(EntryId.MINE, EntryId.clean(name, "profile"));
        return entry(library.mine().write(id, write(profile)));
    }

    /** 改我的档案(同一个 id);没有这一条(或它是内置的)是 {@code null}。改名走另一条:删旧建新,id 随之更换。 */
    public Entry update(String id, ConnectionProfile profile) {
        EntryId entry = EntryId.parse(id);
        if (entry == null || library.mine().get(entry) == null) {
            return null;
        }
        return entry(library.mine().write(entry, write(profile)));
    }

    /** 删我的档案。绑定跟着同伴走,这里不管;内置的由管理员在服务器目录里删。 */
    public void remove(String id) {
        EntryId entry = EntryId.parse(id);
        if (entry != null) {
            library.mine().delete(entry);
        }
    }

    private static Entry entry(Library.Entry entry) {
        return entry(entry.item());
    }

    /** 一个文件读成档案;管理员手写坏了的文件记一笔并当作没有,不连累别的档案。 */
    private static Entry entry(Shelf.Item item) {
        try {
            return new Entry(item.id().toString(), item.id().name(), read(item.text()));
        } catch (RuntimeException unreadable) {
            com.dwinovo.numen.api.Constants.LOG.warn("[numen-providers] {} 读不通,跳过: {}", item.path(),
                    unreadable.toString());
            return null;
        }
    }

    // ---- 档案的 JSON(文件里的样子,也是经库协议传的样子):没有 key ----

    /** 档案文件的正文读成档案内容。 */
    public static ConnectionProfile read(String json) {
        return profileFromJson(com.google.gson.JsonParser.parseString(json).getAsJsonObject());
    }

    /** 档案内容写成文件的正文。 */
    public static String write(ConnectionProfile profile) {
        return profileToJson(profile).toString();
    }

    public static ConnectionProfile profileFromJson(JsonObject o) {
        return new ConnectionProfile(
                strOrNull(o, "provider"), strOrNull(o, "protocol"), strOrNull(o, "model"),
                null, strOrNull(o, "base_url"), strOrNull(o, "proxy"),
                strOrNull(o, "reasoning_effort"),
                o.has("ctx") && o.get("ctx").isJsonPrimitive() ? o.get("ctx").getAsInt() : 0,
                o.has("temperature") && o.get("temperature").isJsonPrimitive()
                        ? o.get("temperature").getAsDouble() : null,
                o.has("max_tokens") && o.get("max_tokens").isJsonPrimitive() ? o.get("max_tokens").getAsInt() : 0,
                o.has("compat") && o.get("compat").isJsonObject() ? o.getAsJsonObject("compat") : null);
    }

    /** {@link #profileFromJson} 的逆。 */
    public static JsonObject profileToJson(ConnectionProfile p) {
        JsonObject o = new JsonObject();
        if (nb(p.provider())) o.addProperty("provider", p.provider());
        if (nb(p.protocol())) o.addProperty("protocol", p.protocol());
        if (nb(p.model())) o.addProperty("model", p.model());
        if (nb(p.baseUrl())) o.addProperty("base_url", p.baseUrl());
        if (nb(p.reasoningEffort())) o.addProperty("reasoning_effort", p.reasoningEffort());
        if (nb(p.proxy())) o.addProperty("proxy", p.proxy());
        if (p.ctx() > 0) o.addProperty("ctx", p.ctx());
        if (p.temperature() != null) o.addProperty("temperature", p.temperature());
        if (p.maxTokens() > 0) o.addProperty("max_tokens", p.maxTokens());
        if (p.compat() != null && p.compat().size() > 0) o.add("compat", p.compat());
        return o;
    }

    private static String strOrNull(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static boolean nb(String s) {
        return s != null && !s.isBlank();
    }
}
