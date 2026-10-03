package com.dwinovo.numen.cli;

import java.util.regex.Pattern;

/**
 * 她起名、存盘、之后在命令行上点名的东西(设计、区域)的名字规矩,全仓只在这一处:小写字母、数字、下划线、连字符,
 * 字母或数字打头,最长 48。名字会当文件名、存档键与规则里的一截用,所以不带空格、斜杠与大写。
 */
public final class Names {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

    private Names() {}

    /** 合不合名字的规矩。 */
    public static boolean valid(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * 名字合规就原样返回,否则说清能用什么字。
     *
     * @param kind 起名的是什么({@code design}、{@code route}),只进错误消息
     */
    public static String checked(String kind, String name) {
        if (!valid(name)) {
            throw new IllegalArgumentException(kind + " names are lowercase letters, digits, _ and -, starting "
                    + "with a letter or digit, at most 48 long; got \"" + name + "\"");
        }
        return name;
    }
}
