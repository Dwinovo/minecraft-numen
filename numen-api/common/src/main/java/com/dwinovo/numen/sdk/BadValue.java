package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ScriptEngine;

/**
 * 一个值读不成({@link Codec#decode}):要什么样子、给了什么。看得出她想写什么时(三个数想写一格)带上照这一种写法该写成的那个值,
 * 读参数的一处把它写进错误值的 {@code hint}(改好的那一整行调用)。
 */
public final class BadValue extends RuntimeException {

    private final transient Object instead;

    public BadValue(String message) {
        this(message, null);
    }

    /** @param instead 照这一种写法该写成的那个脚本的值;看不出是 null */
    public BadValue(String message, Object instead) {
        super(message, null, false, false);
        this.instead = instead;
    }

    /** 照这一种写法该写成的那个值;看不出是 null。 */
    public Object instead() {
        return instead;
    }

    /** 同一个错,前面说是在哪一处({@code field 'pos'}、{@code item 2});改好的值只对整个值说得通,不再带。 */
    public BadValue in(String where) {
        return new BadValue(where + ": " + getMessage());
    }

    /** 报错里说"你给了什么":这个脚本的值写成的字面量。 */
    public static String given(Object value) {
        return ScriptEngine.IN_USE.value(value);
    }
}
