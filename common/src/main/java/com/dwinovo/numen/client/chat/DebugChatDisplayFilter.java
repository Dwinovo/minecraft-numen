package com.dwinovo.numen.client.chat;

/**
 * 调试显示过滤:原样直出,不剥任何协议记号——{@code <query>} 包装、
 * 注入指令块全部可见。经
 * {@link ChatDisplayFilters#set} 整体切入生效;传 null 回落默认实现。
 */
public final class DebugChatDisplayFilter implements ChatDisplayFilter {

    @Override
    public String filterUserMessage(String raw) {
        return raw == null ? "" : raw;
    }

    @Override
    public String filterAssistantMessage(String raw) {
        return raw == null ? "" : raw;
    }
}
