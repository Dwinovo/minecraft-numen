package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.client.screen.UiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * 同伴对话在聊天框里的唯一出样口——统一格式、统一配色,整体比真人聊天
 * 暗一层,像一条安静的字幕:
 *
 * <pre>
 * 你 → sadasdas:帮我看看矿洞          (整行暗灰;语音带「(语音)」记号)
 * sadasdas:好,这就去……               (名字用主题 reply 色,正文浅灰)
 * </pre>
 *
 * 没有尖括号——不冒充真人玩家发言;瞬态状态(没能送达/先选人/没听清)
 * 一律不进聊天框,走准星提示层 {@code TalkHint#flash}。长回复超过
 * {@link #FOLD_AT} 字折叠,悬停浮全文,完整记录在 G 面板。
 */
public final class ChatLines {

    /** 自己发言:整行暗灰(知道自己说了什么,回显只为日志完整)。 */
    private static final int OWN = 0x8E939B;
    /** 同伴正文:比真人聊天的纯白暗一层的浅灰。 */
    private static final int TEXT = 0xC9CDD3;
    private static final int FOLD_AT = 200;

    private ChatLines() {}

    /** 主人发出的一句(文字或语音),回显为暗灰字幕行。 */
    public static void owner(String companionName, String text, boolean voice) {
        String line = "你 → " + companionName + ":" + (voice ? "(语音) " : "") + text;
        add(Component.literal(line).withColor(OWN));
    }

    /** 同伴的回复:着色名字 + 浅灰正文,超长折叠悬停看全文。 */
    public static void companion(String companionName, String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return;
        }
        MutableComponent line = Component.literal(companionName + ":")
                .withColor(UiTheme.current().reply() & 0xFFFFFF);
        if (flat.length() <= FOLD_AT) {
            line.append(Component.literal(flat).withColor(TEXT));
        } else {
            line.append(Component.literal(flat.substring(0, FOLD_AT) + " ……")
                    .withColor(TEXT)
                    .withStyle(s -> s.withHoverEvent(new HoverEvent(
                            HoverEvent.Action.SHOW_TEXT, Component.literal(flat)))));
        }
        add(line);
    }

    private static void add(Component line) {
        Minecraft.getInstance().gui.getChat().addMessage(line);
    }
}
