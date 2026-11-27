package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.script.ScriptEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code ApiSearch} 工具的答复:她按名字({@code select:a,b})或关键字({@code smelt furnace}、{@code +ysm model})查登记表,得到 Lua 类型注解写的文字。
 * 文字由服务端按活的登记表现算(登记与模块里的注释,见 {@link ApiDocs}),主人客户端上的大脑经座位来要;这里只做"查哪些、返回几个",
 * 每一条怎么写是 {@link ApiDocs} 里那一处。
 *
 * <p>可查的有三种,从大到小:插件(名字空间:它登记的说明加它的组与函数一览)、组(每个函数一行签名)、函数(全部:参数、返回、例子、注意、相关;
 * 含内置 Lua 模块里的函数)。引用到的类附在后面。
 *
 * <p>线程:任何线程。
 */
public final class ApiSearch {

    /** {@code max_results} 没给时返回几条。 */
    public static final int DEFAULT_RESULTS = 5;
    /** {@code max_results} 的上限:每条都带全文,再多就该换个查法。 */
    public static final int MAX_RESULTS = 25;

    private static final String SELECT = "select:";

    private ApiSearch() {}

    /** 一条可查的:什么种类、叫什么、一句说明、再往下的说明(只参与匹配)、怎么写成文字。 */
    private record Entry(Kind kind, String name, String summary, String detail, Supplier<String> text) {}

    /** 可查的种类,按从大到小排:同分时大的在前。 */
    private enum Kind { PLUGIN, GROUP, FUNCTION }

    /**
     * 查。
     *
     * @param query      {@code select:a,b}(按名字取)、一串关键字(最匹配的几条)、前面带 {@code +} 的词是名字里必须有的
     * @param maxResults 关键字查询最多返回几条;不是正数是 {@link #DEFAULT_RESULTS},超过 {@link #MAX_RESULTS} 按上限
     * @param companion  她;决定哪些名字空间看得到(目标模组只在客户端的联动,主人的客户端没报它就不在),null 是都看得到
     * @throws IllegalArgumentException 查询是空的
     */
    public static String search(String query, int maxResults, UUID companion) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is empty: give names as select:a,b or keywords");
        }
        String q = query.strip();
        Modules modules = Modules.of(companion);
        List<Entry> entries = entries(modules, companion);
        if (q.regionMatches(true, 0, SELECT, 0, SELECT.length())) {
            return select(q.substring(SELECT.length()), entries, companion);
        }
        return keywords(q, maxResults <= 0 ? DEFAULT_RESULTS : Math.min(maxResults, MAX_RESULTS), entries, companion);
    }

    // ---- 按名字取 ----

    private static String select(String names, List<Entry> entries, UUID companion) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (Entry e : entries) {
            byName.putIfAbsent(e.name(), e);
            byName.putIfAbsent(engine.pathName(e.name()), e);   // 脚本里写成的样子:until_
        }
        List<String> texts = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String raw : names.split(",")) {
            String name = raw.strip();
            if (name.isEmpty()) {
                continue;
            }
            Entry found = byName.get(name);
            if (found == null) {
                found = byName.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
            }
            if (found == null) {
                unknown.add(name);
            } else if (!texts.contains(found.text().get())) {
                texts.add(found.text().get());
            }
        }
        if (texts.isEmpty()) {
            return noMatch(SELECT + names, companion);
        }
        if (!unknown.isEmpty()) {
            texts.add("No plugin, group or function is named " + String.join(", ", unknown) + ".");
        }
        return String.join("\n\n", texts);
    }

    // ---- 关键字 ----

    private static String keywords(String query, int max, List<Entry> entries, UUID companion) {
        List<String> required = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        for (String word : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (word.startsWith("+")) {
                if (word.length() > 1) {
                    required.add(word.substring(1));
                }
            } else {
                terms.add(word);
            }
        }
        record Scored(Entry entry, int score) {}
        List<Scored> hits = new ArrayList<>();
        for (Entry e : entries) {
            String name = e.name().toLowerCase(Locale.ROOT);
            if (!required.stream().allMatch(name::contains)) {
                continue;
            }
            int score = terms.isEmpty() ? 1 : terms.stream().mapToInt(t -> score(t, e)).sum();
            if (score > 0) {
                hits.add(new Scored(e, score));
            }
        }
        if (hits.isEmpty()) {
            return noMatch(query, companion);
        }
        hits.sort(Comparator.<Scored>comparingInt(h -> -h.score()).thenComparing(h -> h.entry().kind())
                .thenComparing(h -> h.entry().name()));
        List<String> texts = new ArrayList<>();
        for (Scored hit : hits.subList(0, Math.min(max, hits.size()))) {
            texts.add(hit.entry().text().get());
        }
        if (hits.size() > max) {
            texts.add(ScriptEngine.IN_USE.comment((hits.size() - max) + " more match: " + String.join(", ",
                    hits.subList(max, hits.size()).stream().map(h -> h.entry().name()).toList())
                    + ". Raise max_results or narrow the query to read them."));
        }
        return String.join("\n\n", texts);
    }

    /** 一个词对一条的分:名字里有一段正好是它最高,名字里含它、一句说明里有它、更往下的说明里有它依次递减。 */
    private static int score(String term, Entry e) {
        String name = e.name().toLowerCase(Locale.ROOT);
        for (String part : name.split("[._]")) {
            if (part.equals(term)) {
                return 12;
            }
        }
        if (name.contains(term)) {
            return 8;
        }
        if (e.summary().toLowerCase(Locale.ROOT).contains(term)) {
            return 4;
        }
        return e.detail().toLowerCase(Locale.ROOT).contains(term) ? 1 : 0;
    }

    private static String noMatch(String query, UUID companion) {
        List<String> namespaces = ApiDocs.namespaces(companion);
        return "No match for \"" + query + "\". " + (namespaces.isEmpty() ? "There is no Lua API plugin right now."
                : "The Lua API plugins right now: " + String.join(", ", namespaces) + ".");
    }

    // ---- 可查的 ----

    /** 此刻所有可查的,按插件、组、函数各自的名字序。 */
    private static List<Entry> entries(Modules modules, UUID companion) {
        Map<String, ApiDocs.Library> library = ApiDocs.library(modules);
        List<ApiRegistry.Group> groups = ApiDocs.offered(companion);
        List<Entry> out = new ArrayList<>();
        for (String namespace : ApiDocs.namespaces(companion)) {
            String guide = ApiRegistry.guide(namespace);
            out.add(new Entry(Kind.PLUGIN, namespace, guide == null ? "" : firstLine(guide), guide == null ? "" : guide,
                    () -> plugin(namespace, groups, modules, library)));
        }
        for (ApiRegistry.Group group : groups) {
            out.add(new Entry(Kind.GROUP, group.fullName(), group.summary(), "",
                    () -> ApiDocs.group(group, library, modules, true)));
            for (ApiFunction fn : group.functions()) {
                out.add(new Entry(Kind.FUNCTION, fn.fullName(), fn.summary(), detail(fn),
                        () -> ApiDocs.function(fn, modules)));
            }
        }
        modules.all().forEach((name, module) -> {
            if (groups.stream().noneMatch(g -> g.fullName().equals(name))) {
                out.add(new Entry(Kind.GROUP, name, module.summary() == null ? "" : module.summary(), "",
                        () -> ApiDocs.module(module, library)));
            }
        });
        library.forEach((name, fn) -> out.add(new Entry(Kind.FUNCTION, name,
                ScriptEngine.IN_USE.summaryOf(fn.defined()), String.join(" ", fn.defined().doc()),
                () -> ApiDocs.library(fn))));
        return out;
    }

    /** 一个插件:它登记的说明(没有登记就没有),接着它的组与不和组同名的模块各一行。 */
    private static String plugin(String namespace, List<ApiRegistry.Group> groups, Modules modules,
                                 Map<String, ApiDocs.Library> library) {
        List<String> lines = new ArrayList<>();
        groups.stream().filter(g -> g.namespace().equals(namespace)).forEach(g -> lines.add(ApiDocs.groupLine(g, library)));
        modules.all().forEach((name, module) -> {
            if (name.startsWith(namespace + ".") && groups.stream().noneMatch(g -> g.fullName().equals(name))) {
                lines.add(ApiDocs.moduleLine(module, library));
            }
        });
        String guide = ApiRegistry.guide(namespace);
        StringBuilder sb = new StringBuilder();
        if (guide != null) {
            sb.append(guide).append("\n\n");
        }
        if (!lines.isEmpty()) {
            sb.append(ScriptEngine.IN_USE.comment(namespace + ": " + lines.size() + " group" + (lines.size() == 1 ? "" : "s")
                    + ". Select a group for its signatures, or a function for everything about it."));
            lines.forEach(line -> sb.append('\n').append(line));
        }
        return sb.toString().strip();
    }

    /** 一个函数除名字与一句说明之外能被关键字查到的:参数名与说明、注意。 */
    private static String detail(ApiFunction fn) {
        List<String> parts = new ArrayList<>();
        fn.params().forEach(p -> {
            parts.add(p.name());
            if (p.doc() != null) {
                parts.add(p.doc());
            }
        });
        parts.addAll(fn.notes());
        return String.join(" ", parts);
    }

    private static String firstLine(String text) {
        String line = text.strip().lines().filter(l -> !l.isBlank() && !l.startsWith("#")).findFirst().orElse("");
        return line.strip();
    }
}
