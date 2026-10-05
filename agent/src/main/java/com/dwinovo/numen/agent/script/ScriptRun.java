package com.dwinovo.numen.agent.script;

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
     * 交回上一次 API 调用的结局,接着跑到下一次调用或结束。成功返回它的值(不返回值的函数是 nil);失败在调用处抛出错误值
     * ({@link #failure})。
     */
    Step resume(Result result);

    /** 让交出去的那次调用在调用处失败,不执行它(参数对不上这个函数的参数表):抛给脚本的就是这个错误,{@code data} 是 Lua 值。 */
    Step refuse(ApiError why);

    /**
     * 不再跑它了(被打断、这一轮被切断、到了上限):正在等结局的调用处、或正在算的那一条指令处停下,占着的东西放掉。跑完了的
     * 调了没有作用。
     */
    void close();

    /** 到此刻为止用到了(装上了)哪些模块,按先后:记战绩用。 */
    List<String> modules();

    /** 运行走到的下一步。 */
    sealed interface Step permits Call, Done {}

    /**
     * 脚本调了一个 API 函数:执行它,结局经 {@link #resume} 交回。
     *
     * @param line    脚本里调用所在的行(经库函数调到的,是脚本里调那个库函数的那一行)
     * @param args    按顺序的对象(字符串、整数、小数、布尔、列表)
     * @param options 选项,名字到值;没有是空表
     */
    record Call(int line, String group, String name, List<Object> args, Map<String, Object> options)
            implements Step {

        /** 脚本里写的函数名,{@code numen.work.dig}、{@code numen.move.go}(改写规则在 {@link ScriptEngine#functionName})。 */
        public String function() {
            return ScriptEngine.IN_USE.function(group, name);
        }
    }

    /**
     * 运行结束。
     *
     * @param ok      跑到了最后,没有出错
     * @param line    出错在哪一行;跑完是 0
     * @param error   出错的那段文字(错误值的 {@code tostring},语言自己的报错原话);跑完是 null
     * @param value   跑完时脚本返回的值(null、布尔、数、字符串、列表、名字到值的表);没有返回值或没跑完是 null
     * @param failure 没跑完时的错误值,至少有 {@code kind}({@link ErrorKind#wire})与 {@code message};跑完是 null
     */
    record Done(boolean ok, int line, String error, Object value, Map<String, Object> failure) implements Step {}

    /**
     * 一次 API 调用的结局。
     *
     * @param ok    成没成
     * @param value 成功时的值(Lua 值);没有是 null
     * @param error 失败时的错误值({@link #failure} 的字段,{@code fn} 由运行补上);成功是 null
     */
    record Result(boolean ok, Object value, Map<String, Object> error) {

        /** 成功的结局。 */
        public static Result ok(Object value) {
            return new Result(true, value, null);
        }

        /** 失败的结局。 */
        public static Result failed(Map<String, Object> error) {
            return new Result(false, null, error);
        }
    }

    /**
     * 失败的调用抛给脚本的错误值:{@code kind}、{@code message}、{@code fn}(哪个函数),有就带上 {@code hint} 与 {@code data}(失败时
     * 回执里的数据,比如够不着的最近一格)。字段名只在这里。
     */
    static Map<String, Object> failure(String kind, String message, String hint, String fn, Object data) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put(KIND, kind);
        out.put(MESSAGE, message);
        if (hint != null) {
            out.put(HINT, hint);
        }
        if (fn != null) {
            out.put(FN, fn);
        }
        if (data != null) {
            out.put(DATA, data);
        }
        return out;
    }

    /** 错误值的字段。 */
    String KIND = "kind";
    String MESSAGE = "message";
    String HINT = "hint";
    String FN = "fn";
    String DATA = "data";
}
