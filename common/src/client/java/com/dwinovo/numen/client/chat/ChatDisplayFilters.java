package com.dwinovo.numen.client.chat;

/** 当前生效的 {@link ChatDisplayFilter}(客户端单例,可整体替换)。 */
public final class ChatDisplayFilters {

    private static ChatDisplayFilter current = new DefaultChatDisplayFilter();

    private ChatDisplayFilters() {}

    public static ChatDisplayFilter current() {
        return current;
    }

    /** 切换过滤逻辑(传 null 回落默认实现)。 */
    public static void set(ChatDisplayFilter filter) {
        current = filter == null ? new DefaultChatDisplayFilter() : filter;
    }
}
