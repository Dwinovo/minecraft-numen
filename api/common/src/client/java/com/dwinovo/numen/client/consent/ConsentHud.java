package com.dwinovo.numen.client.consent;

import com.dwinovo.numen.client.NumenKeys;
import com.dwinovo.numen.client.agent.KnownSkins;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.screen.settings.HostThemeColors;
import com.dwinovo.numen.client.skin.CompanionFace;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextClip;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.Badge;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/**
 * HUD 上的征询提示条:主人没开任何界面时,左上角一行——谁在问、问的第一件事、撤不回的标记、按哪个键答、
 * 还剩几秒、别的同伴还有几条。只提醒不答复:没开界面时键盘鼠标归游戏,答复在对话键打开的答复框里
 * ({@link ConsentPrompt})。放左上角是让开右上角的状态效果图标与原版 toast。
 */
public final class ConsentHud {

    private static final int EDGE = 4;
    private static final int FACE = 10;
    private static final int PAD = 3;
    private static final int MAX_W = 320;

    private ConsentHud() {}

    /** loader 的 HUD 层每帧调用。 */
    public static void render(GuiGraphics g) {
        com.dwinovo.numen.client.ui.SafeUi.run("consent-hud", () -> renderInner(g));
    }

    private static void renderInner(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        ConsentRequestPayload request = ConsentCards.first();
        if (request == null) {
            return;
        }
        McDrawSurface s = new McDrawSurface(g, mc.font);
        NumenTheme.Colors c = HostThemeColors.current();
        String name = NumenRoster.instance().name(request.companion());
        String what = request.lines().isEmpty() ? "" : request.lines().get(0).text().getString();
        boolean irreversible = request.lines().stream().anyMatch(ConsentRequestPayload.Line::irreversible);
        long left = mc.level == null ? 0 : Math.max(0, request.expiresAtGameTime() - mc.level.getGameTime());
        String tail = I18n.get(ModLanguageData.Keys.CONSENT_HUD_HINT,
                NumenKeys.TALK_COMPANION.getTranslatedKeyMessage().getString())
                + "  " + I18n.get(ModLanguageData.Keys.CONSENT_EXPIRES, (left + 19) / 20);
        int others = ConsentCards.all().size() - 1;
        if (others > 0) {
            tail += "  " + I18n.get(ModLanguageData.Keys.CONSENT_QUEUE, others);
        }
        String mark = I18n.get(ModLanguageData.Keys.CONSENT_IRREVERSIBLE);

        int w = Math.min(MAX_W, g.guiWidth() - EDGE * 2);
        int h = s.lineHeight() + PAD * 2 + 1;
        int x = EDGE;
        int y = EDGE;
        NumenStyle.fieldCard(s, x, y, w, h, c.panelBg(), irreversible ? c.danger() : c.accent());
        int cx = x + PAD;
        int ty = y + PAD + 1;
        CompanionFace.draw(g, request.companion(), KnownSkins.of(request.companion()), cx, y + (h - FACE) / 2, FACE);
        cx += FACE + 4;
        int tailW = s.textWidth(tail);
        int markW = irreversible ? s.textWidth(mark) + 8 : 0;
        String head = (name == null ? "?" : name) + ": " + what;
        String fitted = TextClip.fit(s, head, x + w - PAD - tailW - 6 - markW - cx);
        s.drawText(fitted, cx, ty, c.textPrimary(), false);
        cx += s.textWidth(fitted) + 4;
        if (irreversible) {
            Badge.draw(s, mark, cx, ty - 1, c.danger(), 0xFFFFFFFF);
        }
        s.drawText(tail, x + w - PAD - tailW, ty, c.textMuted(), false);
    }
}
