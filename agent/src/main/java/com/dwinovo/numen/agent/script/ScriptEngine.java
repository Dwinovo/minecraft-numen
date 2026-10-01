package com.dwinovo.numen.agent.script;

import com.dwinovo.numen.agent.script.lua.CobaltLua;

import java.util.List;
import java.util.function.Consumer;

/**
 * 脚本用哪种语言写、怎么读、怎么跑。派发、等身体收尾、上限、打断、回执、脚本名词都与它无关,只经这里认语言;换语言只换
 * {@link #IN_USE} 这一处与它的实现。
 */
public interface ScriptEngine {

    /** 眼下用的那一种。 */
    ScriptEngine IN_USE = new CobaltLua();

    /** 语言的名字,给模型看:{@code Lua 5.2}。 */
    String language();

    /** 收这种程序的工具叫什么:{@code lua}。 */
    String toolName();

    /** 存成文件时的扩展名:{@code .lua}。 */
    String extension();

    /**
     * 一个命令在这种语言里怎么调,给模型看的两三个例子与规则(对象、选项、多词的值、返回值、打印、参数),一段话。
     */
    String howToCall();

    /** 一行说明在这种语言里写成注释的样子(说明从正文开头那行注释读,见 {@link #summary}):{@code -- Dig out an area.}。 */
    String comment(String text);

    /** 读一段正文,不运行:读不通返回语言自己的报错原话(带行号),读得通是 null。 */
    String check(String name, String code);

    /** 一句话说明:正文开头那行注释,去掉注释号;开头不是注释是 null。 */
    String summary(String code);

    /**
     * 开一次运行。读不通的正文也开得出来,第一步就是带着报错的 {@link ScriptRun.Done}。
     *
     * @param name    脚本名,进报错的开头;她当场写的一段是工具名
     * @param args    运行参数
     * @param catalog 能调的命令函数
     * @param printer 打印出的每一行
     */
    ScriptRun start(String name, String code, List<String> args, ScriptCatalog catalog, Consumer<String> printer);
}
