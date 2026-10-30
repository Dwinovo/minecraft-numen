package com.dwinovo.numen.api.sdk;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * 一次调用读好的参数:参数名到 Java 值(经各自的值转换读出来的)。没写的可以不写的参数不在里面。执行函数的{@link ApiFunction.Handler}
 * 拿到的就是它;反射的生产者再把它组成参数 record,代码的生产者直接按名字取。
 */
public final class FnArgs {

    private final Map<String, Object> values;

    FnArgs(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    /** 参数名 → 读好的值,按参数表的先后。 */
    public Map<String, Object> values() {
        return values;
    }

    /**
     * 一个必须写的参数的值;收下余下全部的那个参数是 {@code List}。
     *
     * @throws IllegalArgumentException 没有这个参数(描述里没有它,或它是可以不写的)——写函数的人弄错了名字
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("the argument '" + name + "' is not given: it is not a required "
                    + "argument of this function (use option(\"" + name + "\") for one that may be left out)");
        }
        return (T) value;
    }

    /** 一个可以不写的参数的值;没写是空。 */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> option(String name) {
        return Optional.ofNullable((T) values.get(name));
    }
}
