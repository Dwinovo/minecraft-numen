package com.dwinovo.numen.client.hud;

import com.dwinovo.numen.client.NumenKeys;
import com.dwinovo.numen.client.chat.CompanionChatScreen;
import com.dwinovo.numen.client.screen.UiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * 快捷对话提醒:准星指着自己的同伴时,准星下方浮一行「按 [键] 与 名字
 * 对话」。键名跟着 Controls 里的实际绑定走,改键提示自动变;设置的
 * THEME 区可整体关掉。纯 HUD 文本,不占用世界渲染。
 */
public final class TalkHint {

    private TalkHint() {}

    public static void render(GuiGraphics g) {
        if (!UiTheme.talkHintEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui) {
            return;
        }
        AbstractClientPlayer body = CompanionChatScreen.crosshairCompanion();
        if (body == null) {
            return;
        }
        String name = com.dwinovo.numen.client.agent.NumenRoster.instance().name(body.getUUID());
        if (name == null || name.isBlank()) {
            name = body.getScoreboardName();
        }
        String key = NumenKeys.TALK_COMPANION.getTranslatedKeyMessage().getString();
        String text = "按 [" + key + "] 与 " + name + " 对话";

        Font font = mc.font;
        int x = (g.guiWidth() - font.width(text)) / 2;
        int y = g.guiHeight() / 2 + 16;   // 准星正下方一点,不挡视线焦点
        g.drawString(font, text, x, y, 0xFFFFFFFF, true);
    }
}
