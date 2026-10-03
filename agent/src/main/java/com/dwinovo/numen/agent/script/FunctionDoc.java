package com.dwinovo.numen.agent.script;

import java.util.List;

/**
 * 一个 API 函数的说明,和写它的语言无关:由登记处现算(参数表、声明的返回类型、例子、注意),交给脚本引擎写成语言自己的签名
 * ({@link ScriptEngine#functionText}、{@link ScriptEngine#functionLine})。
 *
 * @param name     脚本里的全名,{@code numen.work.dig}
 * @param summary  一句话说明
 * @param params   参数,按调用时的顺序:按顺序的对象在前,最后一个是选项表(有的话)
 * @param returns  返回什么
 * @param examples 几行能跑的例子
 * @param notes    注意:会不会问主人、是不是长活、不会做什么
 * @param seeAlso  下一步常用的函数,脚本里的全名
 */
public record FunctionDoc(String name, String summary, List<Param> params, ScriptType returns, List<String> examples,
                          List<String> notes, List<String> seeAlso) {

    public FunctionDoc {
        params = List.copyOf(params);
        examples = List.copyOf(examples);
        notes = List.copyOf(notes);
        seeAlso = List.copyOf(seeAlso);
    }

    /**
     * 一个参数。
     *
     * @param several  收一个或几个(按顺序的最后一个对象可以写好几个)
     * @param optional 可以不写
     * @param doc      一句说明;没有是 null
     */
    public record Param(String name, ScriptType type, boolean several, boolean optional, String doc) {}
}
