package com.dwinovo.numen.client.chat;

/**
 * 看<b>请求</b>:这一轮实际发给模型的那份消息,原样。
 *
 * <p>来源是 {@code EntityAgentLoop.modelContextSnapshot()} —— 跟 LLM 路径调的是同一个
 * 方法。所以对话史里没有的东西照样看得见:压缩掉的真的没了,临时挂载的
 * {@code <runtime_state>}/{@code <current_task>} 真的在。
 *
 * <p>正文一个字不改:{@code <query>} 包装、注入的事件块、工具结果全都原样。
 */
public final class RawRequestMode implements ChatDisplayMode {

    @Override
    public boolean showsModelRequest() {
        return true;
    }

    @Override
    public String userText(String raw) {
        return raw == null ? "" : raw;
    }

    @Override
    public String assistantText(String raw) {
        return raw == null ? "" : raw;
    }
}
