package com.dwinovo.numen.agent.script;

import com.dwinovo.numen.agent.script.lua.LuaEngine;

import java.util.List;
import java.util.function.Consumer;

/**
 * 脚本用哪种语言写、怎么读、怎么跑。派发、等身体收尾、上限、打断、回执、脚本名词都与它无关,只经这里认语言;换语言只换
 * {@link #IN_USE} 这一处与它的实现。
 */
public interface ScriptEngine {

    /** 眼下用的那一种。 */
    ScriptEngine IN_USE = new LuaEngine();

    /** 语言的名字,给模型看:{@code Lua 5.2}。 */
    String language();

    /** 收这种程序的工具叫什么:{@code lua}。 */
    String toolName();

    /** 存成文件时的扩展名:{@code .lua}。 */
    String extension();

    /**
     * 一个 API 函数在这种语言里怎么调,给模型看的几个例子与规则(对象、选项、多词的值、返回值、出错、打印、参数),一段话。
     */
    String howToCall();

    /**
     * 一个组名或动作名在这种语言里写成什么:撞上语言自己用掉的名字(关键字、自带的全局)时改写的那一条规则只在这里。
     * 帮助、提示与生成函数都经它,所以写法只有一种。
     */
    String functionName(String name);

    /** 一个名字能不能直接写成选项表的键({@code {type = "world"}}):不是语言的关键字就能。 */
    boolean isKey(String name);

    /** 一个动作在这种语言里的函数全名:{@code move.goto_}。 */
    default String function(String group, String verb) {
        return functionName(group) + "." + functionName(verb);
    }

    /**
     * 一次调用写成这种语言里的样子:{@code work.dig("ores", {count = 2})}。对象是字符串、数、布尔或它们的列表,选项按名字。
     */
    String call(String function, List<Object> objects, java.util.Map<String, Object> options);

    /** 一张名字到值的表写成这种语言里的样子:{@code {alter = "natural"}}。 */
    String table(java.util.Map<String, Object> options);

    /** 一行说明在这种语言里写成注释的样子(说明从正文开头那行注释读,见 {@link #summary}):{@code -- Dig out an area.}。 */
    String comment(String text);

    /** 读一段正文,不运行:读不通返回语言自己的报错原话(带行号),读得通是 null。 */
    String check(String name, String code);

    /** 一句话说明:正文开头那行注释,去掉注释号;开头不是注释是 null。 */
    String summary(String code);

    /**
     * 一段库正文顶层定义的函数,按出现的顺序:函数名({@code work.collect} 或全局的 {@code sweep})、形参、紧挨在定义上面的那几行注释
     * (去掉注释号,连成一段)。
     */
    List<Defined> functions(String code);

    /** 库里定义的一个函数。{@code doc} 是它上面的注释,没写是空串。 */
    record Defined(String name, List<String> params, String doc) {}

    /**
     * 只读一段正文调了哪些 API 函数,不执行:每个宿主函数与库函数都换成只记下调用、返回 nil 的那一种,照常跑这段正文。
     * 给写在文字里的例子用(帮助里的例子、技能与提示里的写法),和真跑同一套从调用到参数的换法。库函数的调用记成
     * {@link ScriptRun.Call},组与动词是它在库里的表名与函数名(全局函数的组是空串)。
     *
     * @throws IllegalArgumentException 读不通(语法错);消息是语言自己的报错原话
     */
    Reading calls(String name, String code, ScriptCatalog catalog);

    /**
     * 只读不跑的结果。
     *
     * @param calls 调到的函数,按先后
     * @param error 跑的时候出的错(调用都返回 nil,拿返回值往下算的写法会在这里停);跑完是 null
     */
    record Reading(List<ScriptRun.Call> calls, String error) {}

    /**
     * 开一次运行。读不通的正文也开得出来,第一步就是带着报错的 {@link ScriptRun.Done}。
     *
     * @param name    脚本名,进报错的开头;她当场写的一段是工具名
     * @param args    运行参数
     * @param catalog 能调的 API 函数与库
     * @param printer 打印出的每一行
     */
    ScriptRun start(String name, String code, List<String> args, ScriptCatalog catalog, Consumer<String> printer);
}
