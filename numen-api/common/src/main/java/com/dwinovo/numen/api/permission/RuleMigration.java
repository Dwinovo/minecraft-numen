package com.dwinovo.numen.api.permission;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 存档里主人层的旧行读档时改写成新的名字:规则判的曾经是"有没有放置记录",现在判的是"这东西是谁的"
 * ({@link Whose})。旧的四个信号一一换成归属组:
 * <table>
 *   <caption>改写</caption>
 *   <tr><td>{@code placed}(别人放的)</td><td>{@code (owner | other)}</td></tr>
 *   <tr><td>{@code self_placed}</td><td>{@code self}</td></tr>
 *   <tr><td>{@code owned}(有主人的实体)</td><td>{@code (self | owner | other)}</td></tr>
 *   <tr><td>{@code self_owned}</td><td>{@code self}</td></tr>
 *   <tr><td>{@code near_placed}</td><td>{@code (near(owner) | near(other))}</td></tr>
 * </table>
 * 只改作为一项出现的整词,不动 id 与标签里碰巧同名的部分。改写后的行经规则核心规范打印,所以 {@code !placed} 成了
 * {@code !(owner | other)}。
 */
final class RuleMigration {

    private static final Map<String, String> RENAMED = Map.of(
            "placed", "(owner | other)",
            "self_placed", "self",
            "owned", "(self | owner | other)",
            "self_owned", "self",
            "near_placed", "(near(owner) | near(other))");

    private static final Pattern OLD_NAME = Pattern.compile("(?<![\\w:#/.-])(self_placed|self_owned|near_placed|placed|owned)(?![\\w:/.-])");

    private RuleMigration() {}

    /** 一行规则原文换成新名字;没有旧名字就原样返回。 */
    static String rewrite(String rule) {
        Matcher m = OLD_NAME.matcher(rule);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(RENAMED.get(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }
}
