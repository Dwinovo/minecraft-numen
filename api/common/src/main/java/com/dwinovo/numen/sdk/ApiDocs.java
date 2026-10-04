package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.FunctionDoc;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.script.Modules;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * API 的说明,全由登记({@link ApiFunction})与模块里函数上面的注释生成,没有手写的第二份。以类型签名呈现(Lua 是 LuaLS 的注解,由脚本
 * 引擎写),按需展开:
 *
 * <ul>
 *   <li>索引({@link #index}):系统提示里的 {@code <api>}——共用的几种值的类声明,每组一行说明与它的函数名,再是模块一个一行;</li>
 *   <li>帮助({@link #help}):{@code numen.api.help("numen.move")} 是一组或一个模块(每个函数一行签名),
 *       {@code numen.api.help("numen.move.go")} 是一个函数的全部(逐个参数、返回值的字段、例子、注意、相关);</li>
 *   <li>写错时附上的用法({@link #usage});</li>
 *   <li>整份 LuaLS 存根({@link #stubs})与机器可读的元数据({@link #metadata}):编辑器、评测与工具读。</li>
 * </ul>
 */
public final class ApiDocs {

    /**
     * 索引里模块那一段的开头,有没有模块都写:模块按名字直接用,不需要也不能 require。
     */
    static final String NO_REQUIRE = "Modules: functions written in " + ScriptEngine.IN_USE.language() + " that a "
            + "program uses by name, like a group: `numen.work.collect()`. You neither need nor can require them; "
            + "there is no require.";

    private ApiDocs() {}

    // ---- 索引 ----

    /**
     * 系统提示里的 API 索引:怎么往下要帮助,共用的类,每个组一行说明与函数名(和组同名的模块的函数也列在那一组下),再是模块:内置的在前、
     * 她的在后,一个一行。按名字排好,字节稳定,不打碎 prompt 缓存。一个组都没有时是空串。
     *
     * @param modules 她能用的模块(主人那一份)
     */
    public static String index(Modules modules) {
        List<ApiRegistry.Group> groups = ApiRegistry.groups();
        if (groups.isEmpty()) {
            return "";
        }
        ScriptEngine engine = ScriptEngine.IN_USE;
        Map<String, Library> library = library(modules);
        List<String> lines = new ArrayList<>();
        lines.add("Call these from the " + engine.toolName() + " tool, always written in full: "
                + "namespace.group.function. `" + Call.of("numen.api.help", "numen.move") + "` lists a group's or a "
                + "module's functions with their types; `" + Call.of("numen.api.help", "numen.move.go")
                + "` explains one in full (every argument, what it returns, examples).");
        lines.add("Values the groups share:");
        for (String shared : LuaCodecs.SHARED) {
            lines.add(classText(LuaCodecs.classNamed(shared), modules));
        }
        lines.add("Groups:");
        Set<String> groupNames = new LinkedHashSet<>();
        for (ApiRegistry.Group group : groups) {
            groupNames.add(group.fullName());
            lines.add(groupLine(group, library));
        }
        List<String> builtin = new ArrayList<>();
        List<String> hers = new ArrayList<>();
        modules.all().forEach((name, m) -> {
            String line = "- " + name + ": " + (m.summary() == null ? "" : m.summary()) + " ("
                    + String.join(", ", functionsOf(name, library)) + ")"
                    + (groupNames.contains(name) ? " — adds to the " + name + " group" : "");
            (m.origin() == Modules.Origin.HERS ? hers : builtin).add(m.origin() == Modules.Origin.CHANGED
                    ? line + " — changed by you" : line);
        });
        lines.add(NO_REQUIRE);
        if (!builtin.isEmpty()) {
            lines.add("Built in, first: use these for common jobs before writing your own.");
            lines.addAll(builtin);
        }
        if (!hers.isEmpty()) {
            lines.add("Yours (" + Call.of("numen.module.list") + " shows how the programs that used them went):");
            lines.addAll(hers);
        }
        return "<api>\n" + String.join("\n", lines) + "\n</api>";
    }

    /** 索引里一组的那一行:{@code numen.work — 说明 dig, fish, collect, mine}(和组同名的模块的函数接在后面)。 */
    private static String groupLine(ApiRegistry.Group group, Map<String, Library> library) {
        List<String> names = new ArrayList<>();
        group.functions().forEach(f -> names.add(f.name()));
        names.addAll(functionsOf(group.fullName(), library));
        return group.fullName() + " — " + group.summary() + " " + String.join(", ", names);
    }

    // ---- 帮助 ----

    /**
     * 一个名字({@code numen}、{@code numen.move}、{@code numen.move.go}、{@code numen.move.to}、{@code my.lumber}、
     * {@code my.lumber.chop})指的名字空间、组、模块或函数的帮助;不认得是 null。和组同名的模块的函数在那一组的帮助里。
     */
    public static String help(String name, Modules modules) {
        Map<String, Library> library = library(modules);
        List<ApiRegistry.Group> inNamespace = ApiRegistry.groupsIn(name);
        if (!inNamespace.isEmpty()) {
            List<String> lines = new ArrayList<>();
            lines.add(name + ": " + inNamespace.size() + " group" + (inNamespace.size() == 1 ? "" : "s") + ". `"
                    + Call.of("numen.api.help", inNamespace.getFirst().fullName()) + "` lists one with its types.");
            inNamespace.forEach(g -> lines.add(groupLine(g, library)));
            return String.join("\n", lines);
        }
        ApiRegistry.Group group = ApiRegistry.group(name);
        if (group != null) {
            return group(group, library, modules);
        }
        ApiFunction fn = ApiRegistry.function(name);
        if (fn != null) {
            return function(fn, modules);
        }
        Modules.Module module = modules.get(name);
        if (module != null) {
            return module(module, library);
        }
        Library defined = library.get(name);
        return defined == null ? null : library(defined);
    }

    /** 一组:说明,每个函数一行签名(模块里定义在这一组表里的接在后面),再是这些签名引用到的类(共用的几种在索引里,不重复)。 */
    private static String group(ApiRegistry.Group group, Map<String, Library> library, Modules modules) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        List<String> lines = new ArrayList<>();
        Set<String> named = new LinkedHashSet<>();
        for (ApiFunction fn : group.functions()) {
            FunctionDoc doc = doc(fn);
            lines.add(engine.functionLine(doc));
            named.addAll(named(doc));
        }
        library.forEach((name, fn) -> {
            if (fn.module().equals(group.fullName())) {
                lines.add(engine.libraryLine(fn.defined()));
            }
        });
        return engine.groupText(group.fullName(), group.summary(), lines) + classes(named, modules);
    }

    /** 一个不和组同名的模块:说明,每个函数一行签名,和组同一种样子。 */
    private static String module(Modules.Module module, Map<String, Library> library) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        List<String> lines = new ArrayList<>();
        library.forEach((name, fn) -> {
            if (fn.module().equals(module.name())) {
                lines.add(engine.libraryLine(fn.defined()));
            }
        });
        return engine.groupText(module.name(), module.summary() == null ? "" : module.summary(), lines);
    }

    /** 一个函数,给全:签名(逐个参数带说明、返回什么)、选项与结果的字段、例子、注意、相关,再是它引用到的类。 */
    static String function(ApiFunction fn, Modules modules) {
        FunctionDoc doc = doc(fn);
        return ScriptEngine.IN_USE.functionText(doc) + classes(named(doc), modules);
    }

    /** 模块函数的帮助:它上面的注释(带类型注解)与定义行,它在哪个模块里(全文用 {@code numen.module.show} 看)。 */
    private static String library(Library fn) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        return engine.libraryText(fn.defined()) + "\n" + engine.comment("Written in " + engine.language()
                + " in the module " + fn.module() + ": " + Call.of("numen.module.show", fn.module()) + " prints it.");
    }

    /**
     * 写错时 {@code usage:} 那一段:怎么调,接着缩进列出例子——例子就是正确的写法,照着改比读语法可靠。
     * {@code numen.work.dig(blocks..., {count=…})}。
     */
    public static String usage(ApiFunction fn) {
        List<String> parts = new ArrayList<>();
        List<String> options = new ArrayList<>();
        for (ApiFunction.Param p : fn.params()) {
            switch (p.role()) {
                case REQUIRED -> parts.add(p.name());
                case OPTIONAL -> parts.add("[" + p.name() + "]");
                case REST -> parts.add(p.name() + "...");
                case OPTION -> options.add(p.name() + "=" + (p.codec().type() == ScriptType.BOOLEAN ? "true" : "…"));
            }
        }
        if (!options.isEmpty()) {
            parts.add("{" + String.join(", ", options) + "}");
        }
        StringBuilder sb = new StringBuilder(fn.fullName()).append('(').append(String.join(", ", parts)).append(')');
        for (String example : fn.examples()) {
            sb.append("\n  e.g. ").append(example);
        }
        return sb.toString();
    }

    /** 一个函数的说明,交给脚本引擎写成签名:按顺序的对象各一个参数,选项合成最后一个选项表,字段带说明。 */
    static FunctionDoc doc(ApiFunction fn) {
        List<FunctionDoc.Param> out = new ArrayList<>();
        List<ScriptType.Field> options = new ArrayList<>();
        for (ApiFunction.Param p : fn.params()) {
            switch (p.role()) {
                case OPTION -> options.add(ScriptType.optional(p.name(), p.codec().type(), p.explained()));
                case REST -> out.add(new FunctionDoc.Param(p.name(), p.codec().type(), true, false, p.explained()));
                default -> out.add(new FunctionDoc.Param(p.name(), p.codec().type(), false,
                        p.role() == ApiFunction.Role.OPTIONAL, p.explained()));
            }
        }
        if (!options.isEmpty()) {
            out.add(new FunctionDoc.Param("opts", new ScriptType.Table(options), false, true, null));
        }
        return new FunctionDoc(fn.fullName(), fn.summary(), out, fn.returns().type(), fn.examples(), fn.notes(),
                fn.seeAlso());
    }

    // ---- 类 ----

    /** 引用到的类的声明,接在后面;共用的几种在索引里,不重复。没有是空串。 */
    private static String classes(Set<String> named, Modules modules) {
        StringBuilder sb = new StringBuilder();
        for (String name : named) {
            ScriptType.Class c = LuaCodecs.classNamed(name);
            if (c != null && !LuaCodecs.SHARED.contains(name)) {
                sb.append("\n\n").append(classText(c, modules));
            }
        }
        return sb.toString();
    }

    /** 一个类的声明,带上写在它的模块里的方法(那个模块此刻在的话)。 */
    static String classText(ScriptType.Class c, Modules modules) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        Modules.Module home = c.home() == null ? null : modules.get(c.home());
        String simple = c.name().substring(c.name().lastIndexOf('.') + 1);
        return engine.classText(c, home == null ? List.of() : engine.methods(simple, home.code()));
    }

    /** 一个函数的参数与返回类型里按名字引用的类,连同这些类的字段里再引用的,按出现的先后。 */
    static Set<String> named(FunctionDoc fn) {
        Set<String> out = new LinkedHashSet<>();
        fn.params().forEach(p -> collect(p.type(), out));
        collect(fn.returns(), out);
        return out;
    }

    private static void collect(ScriptType type, Set<String> out) {
        switch (type) {
            case ScriptType.Named n -> {
                if (out.add(n.name())) {
                    ScriptType.Class c = LuaCodecs.classNamed(n.name());
                    if (c != null) {
                        if (c.parent() != null) {
                            collect(new ScriptType.Named(c.parent()), out);
                        }
                        c.fields().forEach(f -> collect(f.type(), out));
                        if (c.items() != null) {
                            collect(c.items(), out);
                        }
                    }
                }
            }
            case ScriptType.ListOf l -> collect(l.item(), out);
            case ScriptType.MapOf m -> collect(m.value(), out);
            case ScriptType.Union u -> u.options().forEach(o -> collect(o, out));
            case ScriptType.Table t -> t.fields().forEach(f -> collect(f.type(), out));
            case ScriptType.Simple s -> { }
            case ScriptType.Choice c -> { }
        }
    }

    // ---- 模块函数 ----

    /** 模块里定义的一个函数:定义它的模块,与它的定义。 */
    record Library(String module, ScriptEngine.Defined defined) {}

    /** 模块里定义的函数:{@code 模块.函数}({@code numen.work.mine})→ 它,按模块名、模块里出现的顺序。 */
    static Map<String, Library> library(Modules modules) {
        Map<String, Library> out = new LinkedHashMap<>();
        modules.all().forEach((name, module) -> {
            for (ScriptEngine.Defined fn : ScriptEngine.IN_USE.functions(name, module.code())) {
                out.put(fn.name(), new Library(name, fn));
            }
        });
        return out;
    }

    /** 一个模块(或和它同名的组)里的函数名,按出现的先后。 */
    private static List<String> functionsOf(String module, Map<String, Library> library) {
        List<String> names = new ArrayList<>();
        String prefix = module + ".";
        library.forEach((name, fn) -> {
            if (fn.module().equals(module)) {
                names.add(name.substring(prefix.length()));
            }
        });
        return names;
    }

    // ---- 存根与元数据 ----

    /**
     * 整份 API 的 LuaLS 存根:共用的类,每一组(连同它的模块函数)的签名与它引用的类,每个函数的全部说明。放进编辑器的工作区,
     * 写程序时就有补全与类型检查。
     */
    public static String stubs(Modules modules) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        Map<String, Library> library = library(modules);
        StringBuilder sb = new StringBuilder(engine.comment("Numen API — generated from the registry; do not edit."));
        sb.append("\n").append(engine.comment("@meta"));
        for (String shared : LuaCodecs.SHARED) {
            sb.append("\n\n").append(classText(LuaCodecs.classNamed(shared), modules));
        }
        for (ApiRegistry.Group group : ApiRegistry.groups()) {
            sb.append("\n\n").append(group(group, library, modules));
            for (ApiFunction fn : group.functions()) {
                sb.append("\n\n").append(engine.functionText(doc(fn)));
            }
        }
        return sb.toString();
    }

    /**
     * 机器可读的元数据(JSON):每个函数的全名、组、在哪执行、怎么交回、参数(名字、怎么写、类型、说明)、返回类型、例子、注意、相关,
     * 以及声明了的类。给工具读,生成别的视图;core 的 lint 报告把它与 {@link #stubs} 一起写出来。
     */
    public static String metadata() {
        ScriptEngine engine = ScriptEngine.IN_USE;
        JsonArray functions = new JsonArray();
        for (ApiFunction fn : ApiRegistry.functions()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", fn.fullName());
            o.addProperty("summary", fn.summary());
            o.addProperty("side", fn.side().name().toLowerCase(java.util.Locale.ROOT));
            o.addProperty("kind", fn.kind().name().toLowerCase(java.util.Locale.ROOT));
            JsonArray params = new JsonArray();
            for (ApiFunction.Param p : fn.params()) {
                JsonObject param = new JsonObject();
                param.addProperty("name", p.name());
                param.addProperty("role", p.role().name().toLowerCase(java.util.Locale.ROOT));
                param.addProperty("type", engine.typeText(p.codec().type()));
                if (p.doc() != null) {
                    param.addProperty("doc", p.doc());
                }
                if (p.omitted() != null) {
                    param.addProperty("omitted", p.omitted());
                }
                params.add(param);
            }
            o.add("params", params);
            o.addProperty("returns", engine.typeText(fn.returns().type()));
            o.add("examples", new Gson().toJsonTree(fn.examples()));
            o.add("notes", new Gson().toJsonTree(fn.notes()));
            o.add("see_also", new Gson().toJsonTree(fn.seeAlso()));
            functions.add(o);
        }
        JsonObject classes = new JsonObject();
        LuaCodecs.classes().forEach((name, c) -> classes.addProperty(name, engine.classText(c)));
        JsonObject out = new JsonObject();
        out.add("functions", functions);
        out.add("classes", classes);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out);
    }
}
