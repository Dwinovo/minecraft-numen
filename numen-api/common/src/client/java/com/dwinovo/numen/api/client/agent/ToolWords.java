package com.dwinovo.numen.api.client.agent;

import net.minecraft.client.resources.language.I18n;

/**
 * 工具名翻成当前语言的话,界面里说"她在用哪个工具"的唯一一处:对话里的工具条、头顶气泡都用它。
 * 约定键 {@code numen.tool.<工具名>};没有译文的(MCP 外部工具)原样显示。
 */
public final class ToolWords {

    private ToolWords() {}

    public static String label(String name) {
        String key = "numen.tool." + name;
        return I18n.exists(key) ? I18n.get(key) : name;
    }
}
