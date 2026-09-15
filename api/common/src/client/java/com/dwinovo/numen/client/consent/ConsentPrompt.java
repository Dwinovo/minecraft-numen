package com.dwinovo.numen.client.consent;

import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextClip;
import com.dwinovo.numen.client.ui.widget.Badge;
import com.dwinovo.numen.client.ui.widget.Widget;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.permission.ConsentAnswer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.List;
import java.util.function.Supplier;

/**
 * 答复框:她在等主人点头时取代整条输入行——G 面板和 Y 快捷对话是同一张(都由输入行开),和 pi 把编辑器整个换成
 * 选择框同一个做法。
 *
 * <p>上面是谁在问、问的是哪几件(撤不回的标出来)、还剩几秒;下面四项:允许(这次活里同样的都行)、以后都允许
 * (选项上写着会记下哪几行规则)、拒绝,第四项就是一个输入框——选到它直接打字,回车按拒绝连同这句送出
 * (和 pi 的"Type something"、Claude Code 的"否,并告诉它换个做法"同一个意思:主人要说点什么,就是不让她
 * 照原样做)。↑↓ 选、回车确定,或者直接按 1-4。清单里有撤不回的事时不给默认选中,必须主人自己挑。
 *
 * <p>第四项的输入框是输入行自己那一个(屏幕上始终只有一个真输入框),摆在 {@link #noteBox} 那一格、只画下划线,
 * 选中第四项时才接字。Esc 照常关界面,请求留着,右上角的提醒接着挂着。
 */
public final class ConsentPrompt extends Widget {

    /** 输入框摆放的那一格。 */
    public record Box(int x, int y, int w, int h) {}

    /** 清单最多列几行,其余计数。 */
    private static final int MAX_LINES = 4;
    private static final int PAD = 4;
    private static final int LINE_H = 11;
    private static final int ROW_H = 12;
    private static final int NOTE_H = 16;
    /** 行首序号那一截的宽度。 */
    private static final int NUM_W = 12;
    /** 前三项各是一种答复;第四项是写一句再拒绝。 */
    private static final ConsentAnswer.Decision[] CHOICES = {
            ConsentAnswer.Decision.ALLOW_ONCE, ConsentAnswer.Decision.ALLOW_REMEMBER, ConsentAnswer.Decision.DENY};
    private static final int NOTE_ROW = CHOICES.length;

    private final ConsentRequestPayload request;
    private final String name;
    private final boolean irreversible;
    /** 第四项输入框里此刻的字(输入行的输入框)。 */
    private final Supplier<String> note;
    /** 选中的那一项;{@code -1} = 还没选。 */
    private int selected;
    /** 已经交出去了——答复发出到撤回到达之间,再按什么都不重发。 */
    private boolean answered;

    /**
     * @param replaced 同一只同伴上一张框(换了一条请求);主人正在写那一句就接着写,选别的项不带过来——
     *                 清单变了,点头要重新点
     * @param note     第四项输入框里的字
     */
    public ConsentPrompt(ConsentRequestPayload request, ConsentPrompt replaced, Supplier<String> note) {
        this.request = request;
        this.note = note;
        String n = NumenRoster.instance().name(request.companion());
        this.name = n == null ? "?" : n;
        this.irreversible = request.lines().stream().anyMatch(ConsentRequestPayload.Line::irreversible);
        this.selected = replaced != null && replaced.writing() ? NOTE_ROW : irreversible ? -1 : 0;
    }

    public ConsentRequestPayload request() {
        return request;
    }

    /** 选中了第四项:输入框接字。 */
    public boolean writing() {
        return selected == NOTE_ROW;
    }

    /** 第四项输入框没字时写着的那一句。 */
    public String noteHint() {
        return I18n.get(ModLanguageData.Keys.CONSENT_NOTE_ROW, name);
    }

    public int preferredHeight() {
        return PAD * 2 + LINE_H * (1 + listedLines()) + 2 + ROW_H * CHOICES.length + NOTE_H + 2 + LINE_H;
    }

    /** 第四项输入框摆在哪。 */
    public Box noteBox() {
        return new Box(x + PAD + NUM_W, noteTop() + 1, w - PAD * 2 - NUM_W, NOTE_H - 2);
    }

    private int listedLines() {
        return Math.min(MAX_LINES, request.lines().size()) + (request.lines().size() > MAX_LINES ? 1 : 0);
    }

    private int rowsTop() {
        return y + h - PAD - LINE_H - 2 - NOTE_H - ROW_H * CHOICES.length;
    }

    private int noteTop() {
        return rowsTop() + ROW_H * CHOICES.length;
    }

    private String label(ConsentAnswer.Decision decision) {
        return I18n.get(switch (decision) {
            case ALLOW_ONCE -> ModLanguageData.Keys.CONSENT_ALLOW;
            case ALLOW_REMEMBER -> ModLanguageData.Keys.CONSENT_ALLOW_REMEMBER;
            case DENY -> ModLanguageData.Keys.CONSENT_DENY;
        });
    }

    @Override
    public boolean focusable() {
        return true;
    }

    /** 上下与回车;在写那一句时其余键归输入框。 */
    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        switch (keyCode) {
            case KeyCodes.UP -> selected = selected < 0 ? 0 : Math.max(0, selected - 1);
            case KeyCodes.DOWN -> selected = Math.min(NOTE_ROW, selected + 1);
            case KeyCodes.ENTER -> {
                if (writing()) {
                    String said = note.get().strip();
                    if (!said.isEmpty()) answer(ConsentAnswer.Decision.DENY, said);
                } else if (selected >= 0) {
                    answer(CHOICES[selected], "");
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** 数字键:1-3 直接答,4 去写那一句。 */
    @Override
    public boolean charTyped(char ch) {
        int row = ch - '1';
        if (row < 0 || row > NOTE_ROW) {
            return false;
        }
        selected = row;
        if (row < NOTE_ROW) {
            answer(CHOICES[row], "");
        }
        return true;
    }

    /** 点一项选中它,再点一次交出去;点第四项就开始写。 */
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int row = my >= noteTop() ? NOTE_ROW : (int) Math.floor((my - rowsTop()) / ROW_H);
        if (row < 0 || my >= noteTop() + NOTE_H) {
            return true;
        }
        if (row < NOTE_ROW && selected == row) {
            answer(CHOICES[row], "");
        } else {
            selected = row;
        }
        return true;
    }

    /** 交出去:答复发回服务端,再用一条原版 toast 说清答了什么、记下了哪几行规则。框由输入行在撤回之后收起。 */
    private void answer(ConsentAnswer.Decision decision, String said) {
        if (answered) {
            return;
        }
        answered = true;
        ConsentCards.reply(request, decision, said);
        String what = label(decision);
        if (!said.isEmpty()) {
            what += " · " + said;
        }
        if (decision == ConsentAnswer.Decision.ALLOW_REMEMBER) {
            what += " · " + I18n.get(ModLanguageData.Keys.CONSENT_REMEMBERED, String.join("; ", request.remember()));
        }
        ConsentToasts.answered(request.companion(), net.minecraft.network.chat.Component.literal(what));
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        NumenStyle.fieldCard(s, x, y, w, h, c.panelBg(), irreversible ? c.danger() : c.accent());
        int ix = x + PAD;
        int iw = w - PAD * 2;
        int ty = y + PAD;

        // 谁在问 · 还剩几秒 · 别的同伴还有几条在等
        var level = Minecraft.getInstance().level;
        long left = level == null ? 0 : Math.max(0, request.expiresAtGameTime() - level.getGameTime());
        String tail = I18n.get(ModLanguageData.Keys.CONSENT_EXPIRES, (left + 19) / 20);
        int others = ConsentCards.all().size() - 1;
        if (others > 0) {
            tail += " · " + I18n.get(ModLanguageData.Keys.CONSENT_QUEUE, others);
        }
        int tailW = s.textWidth(tail);
        s.drawText(TextClip.fit(s, I18n.get(ModLanguageData.Keys.CONSENT_TITLE, name), iw - tailW - 6),
                ix, ty, c.textPrimary(), false);
        s.drawText(tail, ix + iw - tailW, ty, c.textMuted(), false);
        ty += LINE_H;

        List<ConsentRequestPayload.Line> lines = request.lines();
        String mark = I18n.get(ModLanguageData.Keys.CONSENT_IRREVERSIBLE);
        for (int i = 0; i < Math.min(MAX_LINES, lines.size()); i++) {
            ConsentRequestPayload.Line line = lines.get(i);
            int lx = ix;
            if (line.irreversible()) {
                lx += Badge.draw(s, mark, ix, ty - 1, c.danger(), 0xFFFFFFFF) + 4;
            }
            s.drawText(TextClip.fit(s, line.text().getString(), ix + iw - lx), lx, ty,
                    line.irreversible() ? c.textPrimary() : c.textSecondary(), false);
            ty += LINE_H;
        }
        if (lines.size() > MAX_LINES) {
            s.drawText(I18n.get(ModLanguageData.Keys.CONSENT_MORE, lines.size() - MAX_LINES), ix, ty, c.textMuted(), false);
        }

        int ry = rowsTop();
        for (int row = 0; row < CHOICES.length; row++) {
            ConsentAnswer.Decision decision = CHOICES[row];
            boolean picked = row == selected;
            if (picked) {
                s.fillRoundRect(ix - 2, ry - 1, iw + 4, ROW_H, NumenStyle.RADIUS_SMALL, c.selected());
            }
            int textY = ry + (ROW_H - s.lineHeight()) / 2;
            String head = (row + 1) + "  " + label(decision);
            s.drawText(head, ix, textY, decision == ConsentAnswer.Decision.DENY ? c.danger()
                    : picked ? c.textPrimary() : c.textSecondary(), false);
            String scope = switch (decision) {
                case ALLOW_ONCE -> I18n.get(ModLanguageData.Keys.CONSENT_ALLOW_SCOPE);
                case ALLOW_REMEMBER -> String.join("; ", request.remember());
                case DENY -> "";
            };
            int room = iw - s.textWidth(head) - 8;
            if (!scope.isEmpty() && room > 0) {
                String fitted = TextClip.fit(s, scope, room);
                s.drawText(fitted, ix + iw - s.textWidth(fitted), textY, c.textMuted(), false);
            }
            ry += ROW_H;
        }
        // 第四项:序号,后面就是输入框(输入行画在 noteBox 那一格)
        if (writing()) {
            s.fillRoundRect(ix - 2, ry, iw + 4, NOTE_H, NumenStyle.RADIUS_SMALL, c.selected());
        }
        s.drawText(String.valueOf(NOTE_ROW + 1), ix, ry + (NOTE_H - s.lineHeight()) / 2,
                writing() ? c.textPrimary() : c.textSecondary(), false);
        ry += NOTE_H + 2;
        s.drawText(TextClip.fit(s, I18n.get(writing() ? ModLanguageData.Keys.CONSENT_NOTE
                : ModLanguageData.Keys.CONSENT_KEYS), iw), ix, ry, c.textMuted(), false);
    }
}
