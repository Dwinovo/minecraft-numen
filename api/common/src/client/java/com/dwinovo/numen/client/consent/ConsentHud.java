package com.dwinovo.numen.client.consent;

import com.dwinovo.numen.client.agent.KnownSkins;
import com.dwinovo.numen.client.screen.settings.HostThemeColors;
import com.dwinovo.numen.client.skin.CompanionFace;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * HUD 上的征询卡:主人没开任何界面时,右上角挂着同伴的头像和那张卡(不带输入的那一版)。
 * 他多半正在玩,卡片不挡操作;要答复就按提示的键打开面板,那里的卡能点。
 */
public final class ConsentHud {

    private static final int WIDTH = 230;
    private static final int FACE = 18;
    private static final int EDGE = 8;

    private static final ConsentCard CARD = new ConsentCard(false);

    private ConsentHud() {}

    /** loader 的 HUD 层每帧调用。 */
    public static void render(GuiGraphics g) {
        com.dwinovo.numen.client.ui.SafeUi.run("consent-hud", () -> renderInner(g));
    }

    private static void renderInner(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui) {
            return;
        }
        ConsentRequestPayload request = ConsentCards.first();
        if (request == null) {
            return;
        }
        int w = Math.min(WIDTH, g.guiWidth() - FACE - EDGE * 3);
        int x = g.guiWidth() - w - EDGE;
        CARD.layout(request, x, EDGE, w);
        CompanionFace.draw(g, request.companion(), KnownSkins.of(request.companion()),
                x - FACE - 4, EDGE + 2, FACE);
        CARD.render(g, -1, -1, HostThemeColors.current());
    }
}
