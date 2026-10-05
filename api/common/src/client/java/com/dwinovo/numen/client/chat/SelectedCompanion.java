package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.client.agent.Conversations;

import net.minecraft.client.player.AbstractClientPlayer;

import java.util.List;

/**
 * 当前交互对象:转盘选中的、G 面板对着的那个<b>会话</b>,快捷对话/快捷语音的收件人。
 * 单聊是成员表长度为 1 的会话,所以这里没有"选中一只"和"选中一个群"两种东西。
 *
 * <p>它本身不存状态:选了谁记在会话库里({@link Conversations#select}),随会话一起落盘,
 * 重进游戏还是对着上次那个。断线不用清——名册空了,选中的那个自然解析不出来。
 *
 * <p>目标解析优先级(所见即所说):准星正指着的同伴 &gt; 选中的 &gt; 左栏只有一个时自动选中。
 * 准星指着谁就是走到跟前跟她一个人说,不是跟她所在的群——{@code @} 是远程喊话,这里是面对面。
 * 全落空返回 null——多个可选又没选过时,快捷键会提示先开转盘。客户端主线程专用。
 */
public final class SelectedCompanion {

    private SelectedCompanion() {}

    public static void set(Conversation conversation) {
        Conversations.instance().select(conversation);
    }

    /** 选中的那个,此刻的最新一份;选过的已经不在了则 null。 */
    public static Conversation get() {
        return Conversations.instance().selected();
    }

    /** 快捷键此刻应该对着哪个会话说话。 */
    public static Conversation resolveTarget() {
        Conversations convos = Conversations.instance();
        AbstractClientPlayer aimed = CompanionChatScreen.crosshairCompanion();
        if (aimed != null) {
            return convos.of(aimed.getUUID());
        }
        Conversation picked = convos.selected();
        if (picked != null) {
            return picked;
        }
        List<Conversation> all = convos.all();
        return all.size() == 1 ? all.get(0) : null;
    }
}
