package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.client.agent.Conversations;

import net.minecraft.client.player.AbstractClientPlayer;

import java.util.List;

/**
 * 当前交互对象:同伴转盘选中的那个<b>会话</b>,快捷对话/快捷语音的收件人。
 * 单聊是成员表长度为 1 的会话,所以这里没有"选中一只"和"选中一个群"两种东西。
 * 会话态,断线清空。
 *
 * <p>目标解析优先级(所见即所说):准星正指着的同伴 &gt; 转盘选中
 * &gt; 左栏只有一个时自动选中。准星指着谁就是走到跟前跟她一个人说,不是跟她所在的群——
 * {@code @} 是远程喊话,这里是面对面。全落空返回 null——多个可选又没选过时,
 * 快捷键会提示先开转盘。客户端主线程专用。
 */
public final class SelectedCompanion {

    private static Conversation selected;

    private SelectedCompanion() {}

    public static void set(Conversation conversation) {
        selected = conversation;
    }

    public static Conversation get() {
        return selected;
    }

    /** 快捷键此刻应该对着哪个会话说话。 */
    public static Conversation resolveTarget() {
        Conversations convos = Conversations.instance();
        AbstractClientPlayer aimed = CompanionChatScreen.crosshairCompanion();
        if (aimed != null) {
            return convos.of(aimed.getUUID());
        }
        if (selected != null) {
            Conversation cur = convos.current(selected);
            if (cur != null) return cur;
        }
        List<Conversation> all = convos.all();
        return all.size() == 1 ? all.get(0) : null;
    }

    public static void clear() {
        selected = null;
    }
}
