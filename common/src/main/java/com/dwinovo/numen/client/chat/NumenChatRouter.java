package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.api.NumenGateway;
import com.dwinovo.numen.client.agent.NumenRoster;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 聊天框直连同伴:{@code @名字 消息} 一步到位——寻址明确(只有自己的同伴会
 * 响应)、无模式状态(不存在"忘了退出对话模式把私房话喊上公屏")、管线与
 * G 面板同源({@link NumenGateway#enqueue})。名字没匹配到就放行走公屏,
 * 绝不吞玩家消息。名字补全交给原生的自定义补全弹窗
 * ({@link CompanionCompletions}),这里只管路由。
 */
public final class NumenChatRouter {

    private NumenChatRouter() {}

    /**
     * 尝试把一条聊天消息路由给同伴。@开头且名字命中才接管:消息不进公屏,
     * 本地回显一行 [→ 名字] 内容,正文进同伴的消息队列。
     *
     * @return true = 已接管(调用方应取消原发送)
     */
    public static boolean route(String message) {
        if (message == null || !message.startsWith("@")) {
            return false;
        }
        String body = message.substring(1);
        int space = body.indexOf(' ');
        String name = space < 0 ? body : body.substring(0, space);
        String text = space < 0 ? "" : body.substring(space + 1).trim();
        if (name.isBlank()) {
            return false;
        }
        NumenRoster.Entry match = null;
        for (NumenRoster.Entry entry : NumenRoster.instance().entries()) {
            if (entry.name() != null && entry.name().equalsIgnoreCase(name)) {
                match = entry;
                break;
            }
        }
        if (match == null) {
            return false;   // 不是同伴名:照常走公屏
        }
        Minecraft mc = Minecraft.getInstance();
        if (text.isEmpty()) {
            mc.gui.getChat().addMessage(Component.literal("[" + match.name() + "] 在呢——@"
                    + match.name() + " 后面接上你想说的话"));
            return true;
        }
        boolean accepted = NumenGateway.enqueue(match.uuid(), text);
        mc.gui.getChat().addMessage(Component.literal(
                accepted ? "[→ " + match.name() + "] " + text
                         : "[" + match.name() + "] (没能送达——它可能不在线)"));
        return true;
    }
}
