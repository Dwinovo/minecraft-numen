package com.dwinovo.numen.agent.lua;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 脚本里能调的函数:命令登记处的每个动作一个,{@code 组.动词}。目录由命令层现算交来(见 api 的 {@code NumenCli.luaCatalog}),
 * 这里不另记一份命令表——脚本调一个函数,背后就是那一条命令。
 *
 * @param groups 组名 → 动词名 → 这个动词的函数怎么交回结果
 */
public record LuaCatalog(Map<String, Map<String, Verb>> groups) {

    /**
     * 一个动词的函数。
     *
     * @param returns 命令登记时声明的回值:回执 {@code data} 里的这个键直接作函数的返回值(查询拿来就能循环);
     *                没声明是 null,函数交回整张结果表 {@code {ok, text, data}}
     */
    public record Verb(String returns) {}

    public LuaCatalog {
        TreeMap<String, Map<String, Verb>> copy = new TreeMap<>();
        groups.forEach((group, verbs) -> copy.put(group, Collections.unmodifiableMap(new TreeMap<>(verbs))));
        groups = Collections.unmodifiableMap(copy);
    }

    /** 这个动词;没有是 null。 */
    public Verb verb(String group, String verb) {
        Map<String, Verb> verbs = groups.get(group);
        return verbs == null ? null : verbs.get(verb);
    }
}
