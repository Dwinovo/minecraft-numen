package com.dwinovo.numen.mcp.server;

import java.util.List;

/**
 * 端点替宿主问坐在客户端前的人的一个选择题(MCP 的 elicitation,表单模式):一段话、几个带标题的选项,和一句只在某个选项下
 * 才有意义的附言。宿主决定问什么、每个选项算什么;这里只管问和把人的选择带回来。
 *
 * @param message    问的话
 * @param choices    选项,{@code value} 是带回来的值,{@code title} 是给人看的
 * @param choiceTitle 选择那一栏的标题
 * @param noteTitle   附言那一栏的标题;附言可选
 */
public record McpQuestion(String message, List<Choice> choices, String choiceTitle, String noteTitle) {

    public record Choice(String value, String title) {}

    public McpQuestion {
        choices = List.copyOf(choices);
    }

    /**
     * 人的表态。
     *
     * @param choice 选的那一项的 {@code value};人关掉弹窗没选是 null
     * @param declined 人明确拒绝(点了"拒绝"这一类按钮)而没有选任何一项
     * @param note   附言,没写是空串
     */
    public record Answer(String choice, boolean declined, String note) {}
}
