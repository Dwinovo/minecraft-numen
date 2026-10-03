package com.dwinovo.numen.agent.script;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 脚本里能调的:API 登记处的每个动作一个宿主函数 {@code 组.动词},加上内置库(用脚本语言写、随模组发布的函数)。目录由命令层现算交来
 * (见 api 的 {@code NumenCli.scriptCatalog}),这里不另记一份动作表——脚本调一个函数,背后就是那一个动作。
 *
 * @param groups    组名 → 动词名 → 这个动词的函数怎么交回结果
 * @param libraries 库名 → 正文,按登记顺序;每次运行开跑之前先跑它们,它们定义的函数脚本里直接能调
 */
public record ScriptCatalog(Map<String, Map<String, Verb>> groups, Map<String, String> libraries) {

    /**
     * 一个动词的函数。
     *
     * @param returns 动作登记时声明的回值:回执 {@code data} 里的这个键直接作函数的返回值(查询拿来就能循环);
     *                没声明是 null,成功返回回执数据、失败抛错
     * @param echoed  它成功的调用的参数原样留在脚本回执的 {@code data} 里({@link ScriptCall#ECHOED}):对话流据此画它
     */
    public record Verb(String returns, boolean echoed) {

        public Verb(String returns) {
            this(returns, false);
        }
    }

    public ScriptCatalog {
        TreeMap<String, Map<String, Verb>> copy = new TreeMap<>();
        groups.forEach((group, verbs) -> copy.put(group, Collections.unmodifiableMap(new TreeMap<>(verbs))));
        groups = Collections.unmodifiableMap(copy);
        libraries = Collections.unmodifiableMap(new LinkedHashMap<>(libraries));
    }

    /** 这个动词;没有是 null。 */
    public Verb verb(String group, String verb) {
        Map<String, Verb> verbs = groups.get(group);
        return verbs == null ? null : verbs.get(verb);
    }
}
