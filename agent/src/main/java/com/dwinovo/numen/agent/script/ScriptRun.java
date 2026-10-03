package com.dwinovo.numen.agent.script;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * 一段脚本的一次运行,和写它的语言无关:每调一个 API 函数就交出一个 {@link Call},拿到结局({@link #resume})从调用处接着跑,
 * 直到 {@link Done}。等身体干活时它不占任何线程,也不阻塞谁;派调用、等收尾、被打断都是驱动它的一方({@link ScriptCall})的事。
 *
 * <p>只在驱动它的那个线程上调。
 */
public interface ScriptRun {

    /** 开跑,直到第一次 API 调用或结束。 */
    Step start();

    /**
     * 交回上一次 API 调用的结局,接着跑到下一次调用或结束。声明了返回项的({@link ScriptCatalog.Verb#returns})回执数据里有这一项
     * 就返回它,成败都返回;其余成功返回回执数据(没有数据就是回执那句话),失败在调用处抛出脚本错误。
     */
    Step resume(Result result);

    /** 让交出去的那次调用在调用处失败,不执行它(参数对不上这个动作的参数表);{@code why} 是给脚本的那句话。 */
    Step refuse(String why);

    /**
     * 不再跑它了(被打断、这一轮被切断、到了上限):正在等结局的调用处、或正在算的那一条指令处停下,占着的东西放掉。跑完了的
     * 调了没有作用。
     */
    void close();

    /** 运行走到的下一步。 */
    sealed interface Step permits Call, Done {}

    /**
     * 脚本调了一个 API 函数:执行这个动作,结局经 {@link #resume} 交回。
     *
     * @param line    脚本里调用所在的行(经库函数调到的,是脚本里调那个库函数的那一行)
     * @param args    按顺序的对象(字符串、整数、小数、布尔、列表)
     * @param options 选项,名字到值;没有是空表
     */
    record Call(int line, String group, String verb, List<Object> args, Map<String, Object> options)
            implements Step {

        /** 脚本里写的函数名,{@code work.dig}、{@code move.goto_}(改写规则在 {@link ScriptEngine#functionName})。 */
        public String function() {
            return ScriptEngine.IN_USE.function(group, verb);
        }
    }

    /**
     * 运行结束。
     *
     * @param ok    跑到了最后,没有出错
     * @param line  出错在哪一行;跑完是 0
     * @param error 出错的原话(语言自己的写法);跑完是 null
     * @param value 跑完时脚本返回的值(null、布尔、数、字符串、列表、名字到值的表);没有返回值或没跑完是 null
     */
    record Done(boolean ok, int line, String error, Object value) implements Step {}

    /** 一次 API 调用的结局:成没成、回执那句话、回执里的数据(没有是空对象)。 */
    record Result(boolean ok, String text, JsonObject data) {}
}
