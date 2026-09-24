package com.dwinovo.numen.client.consent;

import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.Anim;
import com.dwinovo.numen.client.ui.InlineKeyboard;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.permission.ConsentDesk;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 她的征询在屏幕上的样子——Telegram 带内联按钮的消息:
 *
 * <pre>
 * ┌──────────────────────────────┐
 * │ 挖 [原木]×6 · dwinovo 放的     │  ← 气泡里的清单:动词、图标(没有图标写名字)、数量、理由;撤不回的整行警示色
 * │                        87 秒 │  ← 挂着时是剩下的秒数,底边一道缩短的线;收起后是到的时刻
 * └──────────────────────────────┘
 * [1   允许   ] [2  以后都允许 ]      ← 内联按钮:半透明底,等宽,放不下就换行;左上角的序号就是数字键
 * [3   拒绝   ] [4 说一句再拒绝]
 *        [你选了「允许」]              ← 答完:选中的那个键留着,别的淡下去,下面长出一条服务消息写结果
 * </pre>
 *
 * <p>气泡外形与结果那条服务消息由宿主画(对话流里是她的气泡与对话流的服务消息,快捷对话里是输入卡那一套框),
 * 这里只管气泡里的清单、下面的键盘,以及结果写什么、露出几成。按钮的底与字就是服务消息那一对颜色(Telegram 的
 * 内联按钮也是 msgServiceBg / msgServiceFg);几何照 {@code msgBotKbButton}(键间 2px 的缝)按 MC 的字号缩;
 * 序号照 Telegram 的快捷按钮模式标在键的左上角。键盘选中框(↑↓ 选、回车按)Telegram 没有,只在数字键此刻归这条
 * 消息时淡入。
 */
public final class ConsentMessage {

    /** 清单一行的高:物品图标画成 12 像素,比一行字高一点。 */
    private static final int ROW_H = 13;
    /** 清单最多列几堆,其余计数。 */
    private static final int MAX_ROWS = 3;
    /** 图标画成 12 像素(物品原图 16)。 */
    private static final float ICON_SCALE = 0.75f;
    private static final int ICON = 12;
    private static final int KEY_H = 16;
    private static final int KEY_PAD = 10;
    /** 键与键、气泡与键盘、键盘与结果之间的缝。 */
    public static final int GAP = 2;
    /** 答完的过渡:没选的键淡下去、结果长出来。 */
    private static final int SETTLE_MS = 220;
    /** 按下去那一圈漾开再淡掉。 */
    private static final int RIPPLE_MS = 400;
    /** 悬停与选中框的淡入淡出:每秒走完几遍。 */
    private static final float FADE_RATE = 8f;

    private ConsentMessage() {}

    // ---- 清单 ----

    public static int listHeight(ConsentCards.Card card) {
        int n = card.request().lines().size();
        return ROW_H * (Math.min(MAX_ROWS, n) + (n > MAX_ROWS ? 1 : 0));
    }

    /** 清单不截短时最宽的那一行。 */
    public static int listWidth(Font font, ConsentCards.Card card) {
        List<ConsentRequestPayload.Line> lines = card.request().lines();
        int w = lines.size() > MAX_ROWS ? font.width("+" + (lines.size() - MAX_ROWS)) : 0;
        for (int i = 0; i < Math.min(MAX_ROWS, lines.size()); i++) {
            ConsentRequestPayload.Line line = lines.get(i);
            w = Math.max(w, font.width(verb(line)) + 4 + (line.icon() != null ? ICON + 1 : font.width(line.name()))
                    + font.width(count(line)) + font.width(" · " + line.cause().getString()));
        }
        return w;
    }

    public static void drawList(GuiGraphics g, Font font, ConsentCards.Card card, int x, int y, int w) {
        UiTheme th = UiTheme.current();
        List<ConsentRequestPayload.Line> lines = card.request().lines();
        int textDy = (ROW_H - font.lineHeight) / 2 + 1;
        for (int i = 0; i < Math.min(MAX_ROWS, lines.size()); i++) {
            drawRow(g, font, th, lines.get(i), x, y + i * ROW_H + textDy, w);
        }
        if (lines.size() > MAX_ROWS) {
            Nb.text(g, font, "+" + (lines.size() - MAX_ROWS), x, y + MAX_ROWS * ROW_H + textDy, th.textDim());
        }
    }

    private static void drawRow(GuiGraphics g, Font font, UiTheme th, ConsentRequestPayload.Line line,
                                int lx, int textY, int width) {
        int end = lx + width;
        int main = line.irreversible() ? th.fail() : th.text();
        int soft = line.irreversible() ? th.fail() : th.textDim();
        String verb = verb(line);
        Nb.text(g, font, verb, lx, textY, soft);
        lx += font.width(verb) + 4;
        if (line.icon() != null) {
            var pose = g.pose();
            pose.pushPose();
            pose.translate(lx, textY - 2, 0);
            pose.scale(ICON_SCALE, ICON_SCALE, 1f);
            g.renderFakeItem(new ItemStack(line.icon()), 0, 0);
            pose.popPose();
            lx += ICON + 1;
        } else {
            String shown = line.name().getString();
            Nb.text(g, font, shown, lx, textY, main);
            lx += font.width(shown);
        }
        String count = count(line);
        Nb.text(g, font, count, lx, textY, main);
        lx += font.width(count);
        Nb.text(g, font, Nb.clip(font, " · " + line.cause().getString(), end - lx), lx, textY, soft);
    }

    private static String verb(ConsentRequestPayload.Line line) {
        return I18n.get(ModLanguageData.Keys.CONSENT_VERB_PREFIX + line.kind().verb());
    }

    private static String count(ConsentRequestPayload.Line line) {
        return line.count() > 1 ? "×" + line.count() : "";
    }

    /** 清单压成一行字(没有图标):输入框上方那条"说一句再拒绝"提示栏的第二行。 */
    public static String summary(ConsentCards.Card card) {
        List<ConsentRequestPayload.Line> lines = card.request().lines();
        if (lines.isEmpty()) return "";
        ConsentRequestPayload.Line head = lines.get(0);
        String more = lines.size() > 1 ? " +" + (lines.size() - 1) : "";
        return verb(head) + " " + head.name().getString() + count(head) + " · " + head.cause().getString() + more;
    }

    // ---- 挂着的时间 ----

    private static long ticksLeft(ConsentCards.Card card) {
        var level = Minecraft.getInstance().level;
        return level == null ? 0 : Math.max(0, card.request().expiresAtGameTime() - level.getGameTime());
    }

    /** 还剩多少秒("87 秒")。 */
    public static String countdown(ConsentCards.Card card) {
        return I18n.get(ModLanguageData.Keys.CONSENT_SECONDS, (ticksLeft(card) + 19) / 20);
    }

    /** 还剩几成:气泡底边那道线画多长。 */
    public static float timeLeft(ConsentCards.Card card) {
        return (float) Math.min(1.0, (double) ticksLeft(card) / ConsentDesk.TIMEOUT_TICKS);
    }

    /** 这条的强调色:清单里有撤不回的事是警示色。 */
    public static int tone(ConsentCards.Card card) {
        UiTheme th = UiTheme.current();
        return card.irreversible() ? th.fail() : th.accent();
    }

    // ---- 内联键盘 ----

    private static int[] labelWidths(Font font) {
        int[] widths = new int[ConsentCards.BUTTONS];
        for (int i = 0; i < widths.length; i++) widths[i] = font.width(ConsentCards.Card.label(i));
        return widths;
    }

    private static List<InlineKeyboard.Key> keys(Font font, int w) {
        return InlineKeyboard.layout(labelWidths(font), w, KEY_PAD, GAP, KEY_H);
    }

    /** 四个键排成一行要多宽:气泡被它撑宽(Telegram 的键盘与气泡同宽)。 */
    public static int keyboardWidth(Font font) {
        return InlineKeyboard.naturalWidth(labelWidths(font), KEY_PAD, GAP);
    }

    public static int keyboardHeight(Font font, int w) {
        return InlineKeyboard.height(keys(font, w));
    }

    public static void drawKeyboard(GuiGraphics g, Font font, ConsentCards.Card card, int x, int y, int w,
                                    int mouseX, int mouseY) {
        UiTheme th = UiTheme.current();
        long now = System.currentTimeMillis();
        float dt = card.frameAt == 0 ? 0f : Math.min(0.1f, (now - card.frameAt) / 1000f);
        card.frameAt = now;
        card.armedShown = fade(card.armedShown, card.armed && card.selected() >= 0, dt);
        float settled = reveal(card);
        // 宿主此刻的透明度(对话流里新消息飞入时整块在淡入):字的淡入淡出乘在它上面,画完还给它
        float base = RenderSystem.getShaderColor()[3];
        List<InlineKeyboard.Key> keys = keys(font, w);
        for (int i = 0; i < keys.size(); i++) {
            InlineKeyboard.Key k = keys.get(i);
            int kx = x + k.x(), ky = y + k.y();
            boolean hot = card.waiting() && k.contains(mouseX - x, mouseY - y);
            // 点过的那个停在按下的样子:答完是选中的那个,写着那一句时是"说一句再拒绝"
            boolean held = i == card.chosen() || (i == ConsentCards.NOTE && card.writing());
            card.over[i] = fade(card.over[i], hot || held, dt);
            // 答完:没选的键淡下去,选中的那个留着
            float alpha = i == card.chosen() ? 1f : 1f - 0.55f * settled;
            g.fill(kx, ky, kx + k.w(), ky + k.h(), withAlpha(th.serviceBg(), alpha));
            if (card.over[i] > 0f) {
                g.fill(kx, ky, kx + k.w(), ky + k.h(), withAlpha(th.botKbOver(), card.over[i] * alpha));
            }
            if (i == card.pressedKey) {
                drawRipple(g, card, k, kx, ky, now, th);
            }
            if (i == card.selected() && card.armedShown > 0f) {
                Nb.border(g, kx, ky, k.w(), k.h(), 1, withAlpha(th.serviceFg(), card.armedShown * alpha));
            }
            String label = Nb.clip(font, ConsentCards.Card.label(i), k.w() - KEY_PAD * 2);
            int ty = ky + (k.h() - font.lineHeight) / 2 + 1;
            g.setColor(1f, 1f, 1f, base * alpha * 0.6f);
            Nb.text(g, font, String.valueOf(i + 1), kx + 3, ky + 2, th.serviceFg());
            g.setColor(1f, 1f, 1f, base * alpha);
            Nb.text(g, font, label, kx + (k.w() - font.width(label)) / 2, ty, th.serviceFg());
            g.setColor(1f, 1f, 1f, base);
        }
    }

    /** 按下去的那一圈(Telegram 的按钮波纹,方角版):从按下的那一点往外漾开,边走边淡,裁在键里。 */
    private static void drawRipple(GuiGraphics g, ConsentCards.Card card, InlineKeyboard.Key k, int kx, int ky,
                                   long now, UiTheme th) {
        float t = (now - card.pressedAt) / (float) RIPPLE_MS;
        if (t >= 1f) {
            card.pressedKey = -1;
            return;
        }
        int r = Math.round(Anim.easeOutCubic(t) * Math.max(k.w(), k.h()));
        int x0 = Math.max(kx, kx + card.pressX - r), x1 = Math.min(kx + k.w(), kx + card.pressX + r);
        int y0 = Math.max(ky, ky + card.pressY - r), y1 = Math.min(ky + k.h(), ky + card.pressY + r);
        g.fill(x0, y0, x1, y1, withAlpha(th.botKbRipple(), 1f - t));
    }

    /** 点在键盘上:按那个键;点在键盘里就算点中了这条消息,不往下传。 */
    public static boolean click(Font font, ConsentCards.Card card, int x, int y, int w, double mx, double my) {
        List<InlineKeyboard.Key> keys = keys(font, w);
        for (int i = 0; i < keys.size(); i++) {
            InlineKeyboard.Key k = keys.get(i);
            if (!k.contains(mx - x, my - y)) continue;
            if (card.waiting()) {
                card.pressedKey = i;
                card.pressedAt = System.currentTimeMillis();
                card.pressX = (int) (mx - x) - k.x();
                card.pressY = (int) (my - y) - k.y();
                card.press(i);
            }
            return true;
        }
        return false;
    }

    // ---- 结果 ----

    /** 收起以后结果露出几成(0..1):没选的键淡下去、结果那条服务消息长出来都按它。还挂着是 0。 */
    public static float reveal(ConsentCards.Card card) {
        return card.waiting() ? 0f
                : Anim.easeOutCubic((System.currentTimeMillis() - card.settledAt()) / (float) SETTLE_MS);
    }

    /** 收起以后写什么:点了哪个键、拒绝时说的那句、在别处答的、没等到答复为什么撤了。 */
    public static String result(ConsentCards.Card card) {
        if (card.chosen() == ConsentCards.NOTE) {
            return I18n.get(ModLanguageData.Keys.CONSENT_DENIED_SAYING, card.note());
        }
        if (card.chosen() >= 0) {
            return I18n.get(ModLanguageData.Keys.CONSENT_CHOSE, ConsentCards.Card.label(card.chosen()));
        }
        if (card.gone().isEmpty()) {
            return I18n.get(ModLanguageData.Keys.CONSENT_ANSWERED_ELSEWHERE);
        }
        return I18n.get(ModLanguageData.Keys.CONSENT_WITHDRAWN, ConsentCards.name(card.companion()), card.gone());
    }

    // ---- 小工具 ----

    /** 淡入淡出一步:朝 0 或 1 线性走,{@link #FADE_RATE} 分之一秒走完。 */
    private static float fade(float v, boolean on, float dt) {
        return Math.max(0f, Math.min(1f, v + (on ? dt : -dt) * FADE_RATE));
    }

    private static int withAlpha(int argb, float k) {
        return (Math.round((argb >>> 24) * Math.max(0f, Math.min(1f, k))) << 24) | (argb & 0xFFFFFF);
    }
}
