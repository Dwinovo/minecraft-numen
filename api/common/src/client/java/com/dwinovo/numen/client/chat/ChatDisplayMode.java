package com.dwinovo.numen.client.chat;

/**
 * G 面板在看<b>哪一份</b>。只有两份,{@link ChatDisplayModes#set} 整体切换:
 *
 * <ul>
 *   <li>{@link OwnerWordsMode}(常态)——<b>主人和她的对话</b>。画的是对话史,每条只取
 *       {@code <query>} 里主人的原话;协议记号、注入的事件块一概不属于这份;</li>
 *   <li>{@link RawRequestMode}(debug)——<b>这一轮真正发给模型的请求</b>。画的是
 *       {@code EntityAgentLoop.modelContextSnapshot()},原样,一个字不改。</li>
 * </ul>
 *
 * <h2>它不是"剥不剥标签"的开关</h2>
 * 那是结果不是定义。真正的选择是<b>看哪一份</b>——决定了来源,呈现方式跟着来源走:
 * 看对话就只看主人的话,看请求就一个字不改。
 *
 * <p>只当成"要不要剥标签"来实现的话,两份的来源就是同一个(对话史),而请求里临时
 * 挂载的运行期状态({@code <runtime_state>})按定义不在对话史里,于是 debug 开着也
 * 画不出来——主人看到的现象是"我开了 debug 却看不见 {@code <current_task>}",
 * 而那会被读成"这东西根本没发出去"。
 *
 * <p>只影响显示——LLM 收发的内容、落盘的对话记录都不经过这里。
 */
public interface ChatDisplayMode {

    /**
     * 看的是不是<b>请求</b>。{@code true} → 面板取
     * {@code EntityAgentLoop.modelContextSnapshot()}(跟发给 LLM 的是同一个方法,
     * 没有第二份账);{@code false} → 取对话史。
     */
    default boolean showsModelRequest() {
        return false;
    }

    /**
     * user 消息 → 显示文本。返回空串 = 该条不显示(看对话时,纯注入的
     * {@code <event>}/{@code <persona-change>} 就属于这种)。
     */
    String userText(String raw);

    /** assistant 消息 → 显示文本。返回空串 = 该条不显示。 */
    String assistantText(String raw);
}
