package com.dwinovo.numen.agent.script;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * 一段脚本的一次运行,和写它的语言无关:每调一个命令函数就交出一个 {@link Call},拿到结局({@link #resume})从调用处接着跑,
 * 直到 {@link Done}。等身体干活时它不占任何线程,也不阻塞谁;派命令、等收尾、被打断都是驱动它的一方({@link ScriptCall})的事。
 *
 * <p>只在驱动它的那个线程上调。
 */
public interface ScriptRun {

    /** 开跑,直到第一条命令或结束。 */
    Step start();

    /**
     * 交回上一条命令的结局,接着跑到下一条命令或结束。声明了返回项的命令({@link ScriptCatalog.Verb#returns})成功时函数返回
     * 回执数据里的那一项,失败时在调用处抛出脚本错误;其余命令函数返回结果 {@code {ok, text, data}}。
     */
    Step resume(Result result);

    /** 让交出去的那条命令在调用处失败,不执行它(写法不合这个动作的参数表);{@code why} 是给脚本的那句话。 */
    Step refuse(String why);

    /** 运行走到的下一步。 */
    sealed interface Step permits Call, Done {}

    /**
     * 脚本调了一个命令函数:执行这条命令,结局经 {@link #resume} 交回。
     *
     * @param line    脚本里调用所在的行
     * @param args    按顺序的对象(字符串、整数、小数、布尔、列表)
     * @param options 选项,名字到值;没有是空表
     */
    record Call(int line, String group, String verb, List<Object> args, Map<String, Object> options)
            implements Step {

        /** 脚本里写的函数名,{@code work.dig}。 */
        public String function() {
            return group + "." + verb;
        }
    }

    /**
     * 运行结束。
     *
     * @param ok    跑到了最后,没有出错
     * @param line  出错在哪一行;跑完是 0
     * @param error 出错的原话(语言自己的写法);跑完是 null
     */
    record Done(boolean ok, int line, String error) implements Step {}

    /** 一条命令的结局:成没成、回执那句话、回执里的数据(没有是空对象)。 */
    record Result(boolean ok, String text, JsonObject data) {}
}
