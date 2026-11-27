package com.dwinovo.numen.api.script;

import com.dwinovo.numen.api.plugin.ClientMods;
import com.dwinovo.numen.script.ScriptCatalog;
import com.dwinovo.numen.script.ScriptEngine;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 一段程序能用的 Lua 模块:随模组与插件发布的内置模块({@link BuiltinModules}),每个返回一张函数表,程序里以模块名直接用
 * ({@code plugin.work.collect()}),和第 ① 层的组同名的给那一组加函数。名字两段 {@code 名字空间.组}。目标模组只在客户端的联动带的模块,
 * 主人的客户端没报它就不是她能用的({@link ClientMods})。
 *
 * <p>它就是程序的模块来源({@link ScriptCatalog.ModuleSource}):程序用到一个模块时才来读它。
 *
 * <p>线程:不可变,任何线程。
 */
public final class Modules implements ScriptCatalog.ModuleSource {

    /**
     * 一份模块。
     *
     * @param summary 正文开头那行注释;没写是 null
     */
    public record Module(String name, String code, String summary) {}

    /** 这是谁的模块;只读随模组发布的文字(技能、提示里的例子、写法检查)是 null。 */
    private final UUID companion;

    private Modules(UUID companion) {
        this.companion = companion;
    }

    /** 她的这一段程序能用的模块。 */
    public static Modules of(UUID companion) {
        return new Modules(companion);
    }

    /** 只有随模组发布的那一套:读它们的文字(技能、提示里的例子、写法检查)时用,不看谁的模块。 */
    public static Modules factory() {
        return new Modules(null);
    }

    /** 这是谁的模块;没有是 null。帮助里哪些名字空间看得到,按她的主人的客户端提供什么算({@code ClientMods})。 */
    public UUID companion() {
        return companion;
    }

    /** 一个模块名合不合规矩:两段 {@code 名字空间.组},写得出来、不撞语言与引擎的全局。能是 null,不能是那句话。 */
    public static String problem(String name) {
        return ScriptEngine.IN_USE.moduleName(name);
    }

    /** 叫这个名字的那一份;没有是 null。 */
    public Module get(String name) {
        BuiltinModules.Builtin builtin = BuiltinModules.get(name);
        return builtin == null ? null : new Module(name, builtin.code(), builtin.summary());
    }

    /** 程序用到这个名字时装的正文。只读——能不能编译与装进虚拟机无关,读不通就是用它的那个程序的错。 */
    @Override
    public String code(String name) {
        BuiltinModules.Builtin builtin = BuiltinModules.get(name);
        return builtin == null ? null : builtin.code();
    }

    @Override
    public List<String> names() {
        // 目标模组只在客户端的联动带的模块:主人的客户端没报它就不是她能用的(见 ClientMods)
        return BuiltinModules.all().keySet().stream().filter(n -> problem(n) == null)
                .filter(n -> companion == null || ClientMods.offered(companion, n.substring(0, n.indexOf('.')))).toList();
    }

    /** 全部的,按名字排。 */
    public SortedMap<String, Module> all() {
        SortedMap<String, Module> out = new TreeMap<>();
        for (String name : names()) {
            Module m = get(name);
            if (m != null) {
                out.put(name, m);
            }
        }
        return Collections.unmodifiableSortedMap(out);
    }
}
