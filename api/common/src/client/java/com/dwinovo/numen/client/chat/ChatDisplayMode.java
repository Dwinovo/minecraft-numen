package com.dwinovo.numen.client.chat;

/**
 * G 面板每条消息怎么渲染。<b>来源不由这里决定</b>——面板画的永远是物理对话史,
 * 也就是真的发生过的那些消息。
 *
 * <p>两种口径,{@link ChatDisplayModes#set} 整体切换:
 * <ul>
 *   <li>{@link OwnerWordsMode}(常态)——user 消息只取 {@code <query>} 里主人的原话;</li>
 *   <li>{@link RawMessageMode}(debug)——原样,{@code <query>} 外的一起画
 *       ({@code <known_blocks>}、{@code <events>}、协议记号)。</li>
 * </ul>
 *
 * <h2>为什么不连来源一起换</h2>
 * 有些东西是每次请求现拼、从不入记录的(如 {@code <current_task>})。把它们混进来画,
 * 得到的是"历史上那条消息 + 此刻的状态"——一条<b>从未被发送过</b>的消息。面板是记录的
 * 视图,就得忠于记录;实时状态该有自己的位置,不该假扮成历史。
 *
 * <p>只影响显示——LLM 收发的内容、落盘的对话记录都不经过这里。
 */
public interface ChatDisplayMode {

    /**
     * user 消息 → 显示文本。返回空串 = 该条不显示(常态下,纯注入的
     * {@code <event>}/{@code <persona-change>} 就属于这种)。
     */
    String userText(String raw);

    /** assistant 消息 → 显示文本。返回空串 = 该条不显示。 */
    String assistantText(String raw);
}
