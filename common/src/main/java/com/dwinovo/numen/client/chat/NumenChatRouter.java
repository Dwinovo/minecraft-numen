package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.api.NumenGateway;
import com.dwinovo.numen.client.agent.NumenRoster;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 聊天框直连同伴:{@code @名字 消息} 一步到位——寻址明确(只有自己的同伴会
 * 响应)、无模式状态(不存在"忘了退出对话模式把私房话喊上公屏")、管线与
 * G 面板同源({@link NumenGateway#enqueue})。名字没匹配到就放行走公屏,
 * 绝不吞玩家消息。
 */
public final class NumenChatRouter {

    private NumenChatRouter() {}

    /** Tab 补全的轮换状态:同一前缀反复按 Tab 在候选间循环。 */
    private static String lastPrefix = "";
    private static int lastIndex = -1;

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

    /**
     * 聊天框 Tab 补全同伴名:输入以 @ 开头且尚未打出空格时,循环补全为
     * {@code @名字 }。
     *
     * @return true = 已处理该按键
     */
    public static boolean tabComplete(EditBox input) {
        String value = input.getValue();
        if (value == null || !value.startsWith("@")) {
            return false;
        }
        int space = value.indexOf(' ');
        if (space >= 0 && space < value.length() - 1) {
            return false;   // 名字后已在写正文,Tab 交还原生
        }
        List<String> names = new ArrayList<>();
        for (NumenRoster.Entry entry : NumenRoster.instance().entries()) {
            if (entry.name() != null && !entry.name().isBlank()) {
                names.add(entry.name());
            }
        }
        if (names.isEmpty()) {
            return false;
        }
        String typed = (space < 0 ? value.substring(1) : value.substring(1, space));
        // 判断这次 Tab 是"新前缀首补"还是"同前缀轮换":上一轮补全的完整名
        // 再次按 Tab 时,input 值就是那个名字本身。
        String prefix;
        if (lastIndex >= 0 && !lastPrefix.isEmpty()
                && typed.toLowerCase(Locale.ROOT).startsWith(lastPrefix.toLowerCase(Locale.ROOT))
                && matchesAny(names, typed)) {
            prefix = lastPrefix;
        } else {
            prefix = typed;
            lastIndex = -1;
        }
        List<String> candidates = new ArrayList<>();
        for (String n : names) {
            if (n.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                candidates.add(n);
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }
        lastPrefix = prefix;
        lastIndex = (lastIndex + 1) % candidates.size();
        input.setValue("@" + candidates.get(lastIndex) + " ");
        input.moveCursorToEnd(false);
        return true;
    }

    private static boolean matchesAny(List<String> names, String typed) {
        for (String n : names) {
            if (n.equalsIgnoreCase(typed) || (typed.endsWith(" ") && n.equalsIgnoreCase(typed.trim()))) {
                return true;
            }
        }
        return false;
    }
}
