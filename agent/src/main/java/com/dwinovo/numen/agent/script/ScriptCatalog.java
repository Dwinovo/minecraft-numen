package com.dwinovo.numen.agent.script;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 脚本里能调的:API 登记处的每个动作一个宿主函数 {@code 名字空间.组.动词}(第 ① 层,{@code numen.work.dig}),加上模块(用脚本语言写的
 * 库,按名字直接用:{@code my.lumber.chop(…)};和组同名的模块给那一组加函数)。目录由命令层现算交来(见 api 的
 * {@code NumenCli.scriptCatalog}),这里不另记一份动作表——脚本调一个函数,背后就是那一个动作。
 *
 * @param groups  组的全名({@code numen.work})→ 动词名 → 这个动词的函数怎么交回结果
 * @param modules 模块从哪来:用到时才问,所以每次运行读到的是此刻的正文
 */
public record ScriptCatalog(Map<String, Map<String, Verb>> groups, ModuleSource modules) {

    /** 模块从哪来。两个方法都可能在脚本的线程上被调。 */
    public interface ModuleSource {

        /** 叫这个名字({@code numen.work}、{@code my.lumber})的模块此刻的正文;没有是 null。 */
        String code(String name);

        /** 有哪些模块,按名字排。 */
        java.util.List<String> names();

        /** 一个模块都没有。 */
        ModuleSource NONE = new ModuleSource() {
            @Override
            public String code(String name) {
                return null;
            }

            @Override
            public java.util.List<String> names() {
                return java.util.List.of();
            }
        };
    }

    /**
     * 一个动词的函数。
     *
     * @param returns   动作登记时声明的回值:回执 {@code data} 里的这个键直接作函数的返回值(查询拿来就能循环);
     *                  没声明是 null,成功返回回执数据、失败抛错
     * @param echoed    它成功的调用的参数原样留在脚本回执的 {@code data} 里({@link ScriptCall#ECHOED}):对话流据此画它
     * @param options   它的选项名:写在最后的一张表,键全是这些名字时才是选项表,否则它是一个对象(一个 Pos、一只实体);
     *                  不知道参数表的(只说返回项的那种)是 null,写在最后的名字表都当选项表
     * @param positions 按顺序的对象最多几个;最后一个收一串的是 {@link Integer#MAX_VALUE}
     * @param sample    它返回值的样子(按声明的返回类型现造,数都是 0、列表都是空的):只读不跑一段正文时,调用返回它,取字段的写法
     *                  照样读得通;没有是 null
     */
    public record Verb(String returns, boolean echoed, java.util.Set<String> options, int positions, Object sample) {

        public Verb {
            options = options == null ? null : java.util.Set.copyOf(options);
        }

        /** 只说返回项的那种(不知道参数表):写在最后的名字表都是选项表。 */
        public Verb(String returns) {
            this(returns, false, null, Integer.MAX_VALUE, null);
        }

        /**
         * 写在最后的这张名字表是不是选项表:空表是;键全是选项名是;按顺序的对象已经给满了(再没有能收它的位置)也是——写错了选项名
         * 的,读参数那一处会说是哪个。别的(一个 Pos、一个方块、一只实体)是一个对象。
         */
        public boolean optionsTable(java.util.Map<?, ?> table, int objectsBefore) {
            if (options == null) {
                return true;
            }
            return table.isEmpty() || objectsBefore >= positions
                    || table.keySet().stream().allMatch(k -> options.contains(String.valueOf(k)));
        }
    }

    public ScriptCatalog {
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
