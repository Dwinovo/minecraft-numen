package com.dwinovo.numen.api.script;

import com.dwinovo.numen.api.plugin.ClientMods;
import com.dwinovo.numen.script.ScriptCatalog;
import com.dwinovo.numen.script.ScriptEngine;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 一段程序能用的 Lua 模块:每个模块返回一张函数表,程序里以模块名直接用({@code numen.work.collect()}),和第 ① 层的组同名的给那一组加函数。
 * 名字两段 {@code 名字空间.组}。程序看到两样叠在一起:<b>内置</b>({@link BuiltinModules},随模组与插件发布,服务端自己的)与<b>她的</b>
 * (主人客户端上的,{@code HerModules};程序起跑时随请求报来、服务端按内容哈希取到,见 {@code ModuleCache});同名时她的优先,
 * 她关掉的内置不在其中。这里只读:存、删、还原、战绩都是她的模块的持有者(主人的客户端)的事。
 *
 * <p>它就是程序的模块来源({@link ScriptCatalog.ModuleSource}):程序用到一个模块时才来读它。
 *
 * <p>线程:不可变,任何线程。
 */
public final class Modules implements ScriptCatalog.ModuleSource {

    /** 这一份和内置的是什么关系。 */
    public enum Origin {
        /** 内置的,没改过。 */
        FACTORY,
        /** 内置的,她改过:她的那份盖着它。 */
        CHANGED,
        /** 她自己的:内置没有这一份。 */
        HERS
    }

    /**
     * 一份模块。
     *
     * @param summary 正文开头那行注释;没写是 null
     * @param newer   内置的那份在她改它之后变了,她这份是改过的,没跟着换(只有持有她的模块的一方知道,这里恒为 false)
     * @param problem 正文读不通时的那句话;没事是 null
     */
    public record Module(String name, String code, String summary, Origin origin, boolean newer, String problem) {}

    /** 她自己的模块习惯放的名字空间:没写名字存成 {@code my.module_N}。 */
    public static final String MINE = "my";

    /** 这是谁的模块;只读随模组发布的文字(技能、提示里的例子、写法检查)是 null。 */
    private final UUID companion;
    private final Map<String, String> hers;
    private final Set<String> disabled;

    private Modules(UUID companion, Map<String, String> hers, Set<String> disabled) {
        this.companion = companion;
        this.hers = Map.copyOf(hers);
        this.disabled = Set.copyOf(disabled);
    }

    /**
     * 她的这一段程序能用的模块。
     *
     * @param hers     她自己的模块(含和内置同名、盖着内置的),名字到正文
     * @param disabled 她对自己关掉的内置模块
     */
    public static Modules of(UUID companion, Map<String, String> hers, Set<String> disabled) {
        return new Modules(companion, hers, disabled);
    }

    /** 只有随模组发布的那一套:读它们的文字(技能、提示里的例子、写法检查)时用,不看谁的模块。 */
    public static Modules factory() {
        return new Modules(null, Map.of(), Set.of());
    }

    /** 这是谁的模块;没有是 null。帮助里哪些名字空间看得到,按她的主人的客户端提供什么算({@code ClientMods})。 */
    public UUID companion() {
        return companion;
    }

    /** 她自己的模块(含盖着内置的),名字到正文。 */
    public Map<String, String> hers() {
        return hers;
    }

    /** 她对自己关掉的内置模块。 */
    public Set<String> disabled() {
        return disabled;
    }

    /** 一个模块名合不合规矩:两段 {@code 名字空间.组},写得出来、不撞语言与引擎的全局。能是 null,不能是那句话。 */
    public static String problem(String name) {
        return ScriptEngine.IN_USE.moduleName(name);
    }

    /** 叫这个名字的那一份;没有或被她关掉了是 null。 */
    public Module get(String name) {
        String code = code(name);
        if (code == null) {
            return null;
        }
        BuiltinModules.Builtin builtin = BuiltinModules.get(name);
        Origin origin = !hers.containsKey(name) ? Origin.FACTORY : builtin != null ? Origin.CHANGED : Origin.HERS;
        return new Module(name, code, origin == Origin.FACTORY ? builtin.summary() : ScriptEngine.IN_USE.summary(code),
                origin, false, origin == Origin.FACTORY ? null : ScriptEngine.IN_USE.check(name, code));
    }

    /** 内置的这一份(随模组发布的样子);内置没有这一份是 null。 */
    public Module builtin(String name) {
        BuiltinModules.Builtin builtin = BuiltinModules.get(name);
        return builtin == null ? null : new Module(name, builtin.code(), builtin.summary(), Origin.FACTORY, false, null);
    }

    /**
     * 程序用到这个名字时装的正文:她的那份,没有就是内置的(她没关掉的)。只读——能不能编译与装进虚拟机无关,读不通就是用它的那个
     * 程序的错。
     */
    @Override
    public String code(String name) {
        String mine = hers.get(name);
        if (mine != null) {
            return mine;
        }
        BuiltinModules.Builtin builtin = disabled.contains(name) ? null : BuiltinModules.get(name);
        return builtin == null ? null : builtin.code();
    }

    @Override
    public List<String> names() {
        java.util.Set<String> all = new java.util.TreeSet<>(BuiltinModules.all().keySet());
        all.removeAll(disabled);
        all.addAll(hers.keySet());
        // 目标模组只在客户端的联动带的模块:主人的客户端没报它就不是她能用的(见 ClientMods)
        return all.stream().filter(n -> problem(n) == null)
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
