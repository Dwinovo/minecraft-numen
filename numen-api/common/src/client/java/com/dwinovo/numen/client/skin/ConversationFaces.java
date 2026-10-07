package com.dwinovo.numen.client.skin;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.client.agent.Conversations;
import com.dwinovo.numen.client.agent.KnownSkins;
import com.dwinovo.numen.client.agent.NumenRoster;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 一个会话的头像:就他俩是她的脸;群是一块色底加群名的首字(Telegram 的群头像),颜色按群取七色之一,
 * 同一个群永远同一色。左栏、转盘、资料页的共同群聊都画这一个,群头像只在这里定。
 */
public final class ConversationFaces {

    /** Telegram 头像底色的七种(红、橙、紫、绿、青、蓝、粉),日间夜间同一套。 */
    private static final int[] GROUP_BG = {
            0xFFFF845E, 0xFFFEBB5B, 0xFFB694F9, 0xFF9AD164, 0xFF5BCBE3, 0xFF5CAFFA, 0xFFFF8AAC};

    private ConversationFaces() {}

    public static void draw(GuiGraphics g, Conversation c, int x, int y, int size) {
        UUID her = Conversations.instance().soloOf(c);
        if (her != null) {
            CompanionFace.draw(g, her, KnownSkins.of(her), x, y, size);
            return;
        }
        g.fill(x, y, x + size, y + size, GROUP_BG[Math.floorMod(c.id().hashCode(), GROUP_BG.length)]);
        // 首字按整数倍放大,像素字才不糊;小头像就原大
        var font = Minecraft.getInstance().font;
        Component letters = Component.literal(initials(c.displayName(NumenRoster.instance()::name)))
                .withStyle(ChatFormatting.BOLD);
        int scale = Math.max(1, size / 16);
        int tw = font.width(letters) * scale, th = 8 * scale;
        g.pose().pushPose();
        g.pose().translate(x + (size - tw) / 2f, y + (size - th) / 2f + scale * 0.5f, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, letters, 0, 0, 0xFFFFFFFF, false);
        g.pose().popPose();
    }

    /**
     * 群名的首字:前两段各取第一个字(Telegram 取前两个词的首字母);没起名的群名是成员名用"、"连起来的,
     * 于是就是前两位成员的首字。拉丁字母转大写。
     */
    static String initials(String name) {
        StringBuilder out = new StringBuilder();
        for (String part : name.split("[、,，\\s]+")) {
            if (part.isEmpty()) continue;
            out.appendCodePoint(Character.toUpperCase(part.codePointAt(0)));
            if (out.codePointCount(0, out.length()) == 2) break;
        }
        return out.isEmpty() ? "?" : out.toString();
    }
}
