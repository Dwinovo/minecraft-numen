package com.dwinovo.numen.api.agent.tool;

import net.minecraft.network.chat.Component;

/**
 * 一次工具调用在主人面前怎么读:工具的名字,后面跟这个工具说这次调用读作什么({@link NumenTool#summary}),没有可说的就只有名字。
 * 聊天里的工具条与"她此刻在干什么"都按这一条规则写,只在这里定。名字的译文约定键是 {@code numen.tool.<工具名>}。
 */
public final class ToolLine {

    /** 工具名译文的键前缀。 */
    public static final String KEY_PREFIX = "numen.tool.";

    private static final String SEPARATOR = "  ";

    private ToolLine() {}

    /** 工具名译文的键。 */
    public static String key(String tool) {
        return KEY_PREFIX + tool;
    }

    /** {@code label} 是已翻成当前语言的工具名。 */
    public static String of(String label, String summary) {
        return summary.isEmpty() ? label : label + SEPARATOR + summary;
    }

    /** 同 {@link #of(String, String)},名字是一段待翻的文字。 */
    public static Component of(Component label, String summary) {
        return summary.isEmpty() ? label : Component.empty().append(label).append(SEPARATOR + summary);
    }
}
