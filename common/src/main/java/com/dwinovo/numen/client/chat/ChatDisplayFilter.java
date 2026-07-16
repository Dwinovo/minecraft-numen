package com.dwinovo.numen.client.chat;

/**
 * 聊天显示过滤器:协议层的原始文本 → 面板/气泡上呈现给玩家的文本。
 * 仅影响显示——LLM 收发的内容、落盘的对话记录都不经过这里。
 *
 * <p>实现多态:{@link ChatDisplayFilters#set} 整体切换生效的过滤逻辑
 * (默认 {@link DefaultChatDisplayFilter} 剥协议记号;将来 debug 模式提供
 * 一个原样直出的实现即可全量透视)。
 */
public interface ChatDisplayFilter {

    /**
     * user 消息(主人发出的):原始内容 → 显示文本。返回空串 = 该条不显示
     * (纯注入内容,如 {@code <event>}/{@code <persona-change>})。
     */
    String filterUserMessage(String raw);

    /** assistant 消息(同伴回复的):原始内容 → 显示文本。返回空串 = 该条不显示。 */
    String filterAssistantMessage(String raw);
}
