package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.agent.scope.Handle;
import com.dwinovo.numen.script.ScriptCatalog;
import com.dwinovo.numen.script.ScriptEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 登记处:名字空间里有哪些组,一组里有哪些函数。登记表是进程级的静态表,由各模组的公共初始化代码登记——主人客户端与服务端各跑一遍
 * 同一份登记。Numen 自己(引擎与内容)与插件走同一扇门 {@code NumenPlugins.register(名字空间, n -> n.api(组, 说明, 类))}。
 *
 * <p>名字空间由登记者给出;组名在名字空间里谁先登记归谁,插件拿到的门只往自己的名字空间里登记——碰不到别人的组由形状保证。
 * 硬错误(名字写不出来、组已经有主、函数绑定不了,见 {@link Binder})在登记那一刻抛出;风格交给 {@link ApiTester#lint}。
 */
public final class ApiRegistry {

    private ApiRegistry() {}

    /**
     * 一个组。
     *
     * @param functions 按名字排好
     */
    public record Group(String namespace, String name, String summary, List<ApiFunction> functions) {

        /** 组的全名:{@code numen.work}。 */
        public String fullName() {
            return namespace + "." + name;
        }

        /** 这个名字的函数;没有是 null。 */
        public ApiFunction function(String fn) {
            return functions.stream().filter(f -> f.name().equals(fn)).findFirst().orElse(null);
        }
    }

    /** 组的全名 → 组,按名字排序:帮助与系统提示索引的顺序不随插件的加载先后变,字节稳定。 */
    private static final Map<String, Group> GROUPS = new TreeMap<>();

    /** 引擎自己占的全局名:名字空间不能占。 */
    private static final List<String> ENGINE_GLOBALS = List.of("raise", "require");

    /**
     * 名字空间写不写得出来:小写字母开头、{@code [a-z0-9_]},不撞语言的关键字、自带全局与引擎的全局。
     *
     * @throws IllegalArgumentException 写不出来
     */
    public static void checkNamespace(String namespace) {
        String bad = Binder.badName(namespace);
        if (bad == null && ENGINE_GLOBALS.contains(namespace)) {
            bad = "'" + namespace + "' is a global the engine already uses";
        }
        if (bad == null) {
            bad = ScriptEngine.IN_USE.moduleName(namespace + ".x");
        }
        if (bad != null) {
            throw new IllegalArgumentException("namespace " + bad);
        }
    }

    /**
     * 登记一组:{@code functions} 里每个 {@link Fn} 方法是这一组的一个函数(反射读出函数的描述,见 {@link Binder})。
     *
     * @return 撤回这一组的句柄:撤掉后这个全名又没有主了
     * @throws IllegalArgumentException 名字写不出来、这一组已经有主、有函数绑定不了
     */
    public static Handle register(String namespace, String group, String summary, Class<?> functions) {
        return register(namespace, group, summary, Binder.bind(namespace, group, functions));
    }

    /**
     * 登记一组:{@code functions} 是这一组各个函数的描述(反射读的或代码写的,来路一样)。
     *
     * @return 撤回这一组的句柄:撤掉后这个全名又没有主了
     * @throws IllegalArgumentException 名字写不出来、这一组已经有主、没有函数、函数重名
     */
    public static synchronized Handle register(String namespace, String group, String summary,
                                               List<ApiFunction> functions) {
        checkNamespace(namespace);
        String bad = Binder.badName(group);
        if (bad != null) {
            throw new IllegalArgumentException("group " + namespace + "." + group + ": " + bad);
        }
        String full = namespace + "." + group;
        if (GROUPS.containsKey(full)) {
            throw new IllegalArgumentException("the group " + full + " already has an owner: each namespace adds "
                    + "functions only to its own groups");
        }
        if (functions.isEmpty()) {
            throw new IllegalArgumentException("the group " + full + " has no function");
        }
        List<ApiFunction> owned = new ArrayList<>();
        for (ApiFunction fn : functions.stream().sorted(java.util.Comparator.comparing(ApiFunction::name)).toList()) {
            if (!owned.isEmpty() && owned.getLast().name().equals(fn.name())) {
                throw new IllegalArgumentException("the group " + full + " has the function " + fn.name() + " twice");
            }
            owned.add(fn.in(namespace, group));
        }
        Group made = new Group(namespace, group, summary == null ? "" : summary, List.copyOf(owned));
        GROUPS.put(full, made);
        return () -> {
            synchronized (ApiRegistry.class) {
                GROUPS.remove(full, made);
            }
        };
    }

    /** 登记了的各组,按全名排序。 */
    public static synchronized List<Group> groups() {
        return List.copyOf(GROUPS.values());
    }

    /** 这个全名的组({@code numen.work});没有是 null。 */
    public static synchronized Group group(String fullName) {
        return GROUPS.get(fullName);
    }

    /** 这个全名的函数({@code numen.work.dig});没有是 null。 */
    public static synchronized ApiFunction function(String fullName) {
        int dot = fullName.lastIndexOf('.');
        Group group = dot < 0 ? null : GROUPS.get(fullName.substring(0, dot));
        return group == null ? null : group.function(fullName.substring(dot + 1));
    }

    /** 全部函数,按全名排序。 */
    public static List<ApiFunction> functions() {
        List<ApiFunction> out = new ArrayList<>();
        groups().forEach(g -> out.addAll(g.functions()));
        return out;
    }

    /** 一个名字空间下的组,按名字。 */
    public static List<Group> groupsIn(String namespace) {
        return groups().stream().filter(g -> g.namespace().equals(namespace)).toList();
    }

    /**
     * 脚本里能调的:每个登记了的函数一个,加上模块。由登记表现算,不另记一份。
     *
     * @param modules 模块从哪来:她的那一份,或只读随模组发布的文字时只有内置那一层
     */
    public static ScriptCatalog catalog(ScriptCatalog.ModuleSource modules) {
        Map<String, Map<String, ScriptCatalog.Function>> groups = new TreeMap<>();
        for (Group group : groups()) {
            Map<String, ScriptCatalog.Function> functions = new TreeMap<>();
            group.functions().forEach(f -> functions.put(f.name(), f.script()));
            groups.put(group.fullName(), functions);
        }
        return new ScriptCatalog(groups, modules, LuaCodecs.classes());
    }
}
