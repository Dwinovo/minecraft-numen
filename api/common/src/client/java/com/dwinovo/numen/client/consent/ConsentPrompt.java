package com.dwinovo.numen.client.consent;

import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.hud.NumenHudToasts;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.NumenToasts;
import com.dwinovo.numen.client.ui.TextClip;
import com.dwinovo.numen.client.ui.widget.Badge;
import com.dwinovo.numen.client.ui.widget.Popup;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.permission.ConsentAnswer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

/**
 * 答复框:她在等主人点头时取代输入行的那一层——G 面板和 Y 快捷对话是同一张(都由输入行开)。
 *
 * <p>上面是谁在问、问的是哪几件(撤不回的标出来)、还剩几秒;下面四项:允许(这次活里同样的都行)、
 * 以后都允许(选项上写着会记下哪几行规则)、拒绝、不行并告诉她该怎么做。↑↓ 选、回车确定,或者直接按 1-4。
 * 选到第四项就借输入框直接打字,回车按拒绝连同这句送出,↑ 回到选项——和 Claude Code 的"否,并告诉它换个做法"
 * 同一个意思:主人要说点什么,就是不让她照原样做。清单里有撤不回的事时不给默认选中,必须主人自己挑。
 *
 * <p>Esc 不收它:它在等答复,收起了下一刻还在;Esc 照常关界面,请求留着,提示条接着提醒。
 */
public final class ConsentPrompt extends Popup {

    /** 清单最多列几行,其余计数。 */
    private static final int MAX_LINES = 4;
    private static final int PAD = 4;
    private static final int LINE_H = 11;
    private static final int ROW_H = 12;
    /** 前三项各是一种答复;第四项是写一句再拒绝。 */
    private static final ConsentAnswer.Decision[] CHOICES = {
            ConsentAnswer.Decision.ALLOW_ONCE, ConsentAnswer.Decision.ALLOW_REMEMBER, ConsentAnswer.Decision.DENY};
    private static final int NOTE_ROW = CHOICES.length;
    private static final int ROWS = CHOICES.length + 1;

    private final ConsentRequestPayload request;
    private final String name;
    private final boolean irreversible;
    /** 选中的那一项;{@code -1} = 还没选。 */
    private int selected;
    private String note = "";
    /** 选中第四项时借输入框收的那一句;宿主按身份认同一次借用,所以只建一个。 */
    private final LineRequest noteLine = new LineRequest() {
        @Override
        public String hint() {
            return I18n.get(ModLanguageData.Keys.CONSENT_NOTE, name);
        }

        @Override
        public String text() {
            return note;
        }

        @Override
        public void changed(String text) {
            note = text;
        }
    };
    /** 已经交出去了——答复发出到撤回到达之间,再按什么都不重发。 */
    private boolean answered;

    /**
     * @param replaced 同一只同伴上一张框(换了一条请求);主人正在写那一句就接着写,选别的项不带过来——
     *                 清单变了,点头要重新点
     */
    public ConsentPrompt(ConsentRequestPayload request, ConsentPrompt replaced) {
        this.request = request;
        String n = NumenRoster.instance().name(request.companion());
        this.name = n == null ? "?" : n;
        this.irreversible = request.lines().stream().anyMatch(ConsentRequestPayload.Line::irreversible);
        if (replaced != null && replaced.selected == NOTE_ROW) {
            this.selected = NOTE_ROW;
            this.note = replaced.note;
        } else {
            this.selected = irreversible ? -1 : 0;
        }
    }

    public ConsentRequestPayload request() {
        return request;
    }

    /** 一项的名字;回执的 toast 也用这一份。 */
    private String label(int row) {
        if (row == NOTE_ROW) {
            return I18n.get(ModLanguageData.Keys.CONSENT_NOTE_ROW, name);
        }
        return I18n.get(switch (CHOICES[row]) {
            case ALLOW_ONCE -> ModLanguageData.Keys.CONSENT_ALLOW;
            case ALLOW_REMEMBER -> ModLanguageData.Keys.CONSENT_ALLOW_REMEMBER;
            case DENY -> ModLanguageData.Keys.CONSENT_DENY;
        });
    }

    @Override
    public boolean focusable() {
        return true;
    }

    @Override
    public boolean closesOnEscape() {
        return false;
    }

    @Override
    public LineRequest lineRequest() {
        return selected == NOTE_ROW ? noteLine : null;
    }

    @Override
    public int preferredHeight() {
        int lines = Math.min(MAX_LINES, request.lines().size()) + (request.lines().size() > MAX_LINES ? 1 : 0);
        return PAD * 2 + LINE_H * (1 + lines) + 2 + ROW_H * ROWS + LINE_H;
    }

    /** 借着输入框时宿主也先把键给这里:只接上下与回车,别的落到输入框。 */
    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        switch (keyCode) {
            case KeyCodes.UP -> selected = selected < 0 ? 0 : Math.max(0, selected - 1);
            case KeyCodes.DOWN -> selected = Math.min(NOTE_ROW, selected + 1);
            case KeyCodes.ENTER -> {
                if (selected >= 0) choose(selected);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** 数字键:1-3 直接答,4 去写一句。 */
    @Override
    public boolean charTyped(char ch) {
        int row = ch - '1';
        if (row < 0 || row >= ROWS) {
            return false;
        }
        selected = row;
        if (row != NOTE_ROW) {
            choose(row);
        }
        return true;
    }

    /** 点一项选中它,再点一次交出去(第四项点中就开始写)。 */
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int row = (int) Math.floor((my - rowsTop()) / ROW_H);
        if (row < 0 || row >= ROWS) {
            return false;
        }
        if (selected == row && row != NOTE_ROW) {
            choose(row);
        } else {
            selected = row;
        }
        return true;
    }

    /** 交出去:答复发回服务端,再用一条 toast 说清答了什么、记下了哪几行规则。框由输入行在撤回之后收起。 */
    private void choose(int row) {
        if (answered) {
            return;
        }
        answered = true;
        ConsentAnswer.Decision decision = row == NOTE_ROW ? ConsentAnswer.Decision.DENY : CHOICES[row];
        ConsentCards.reply(request, decision, row == NOTE_ROW ? note : "");
        String receipt = I18n.get(ModLanguageData.Keys.CONSENT_ANSWERED, name, label(row));
        if (decision == ConsentAnswer.Decision.ALLOW_REMEMBER) {
            receipt += " · " + I18n.get(ModLanguageData.Keys.CONSENT_REMEMBERED, String.join("; ", request.remember()));
        }
        NumenHudToasts.push(NumenToasts.Severity.INFO, receipt);
    }

    private int rowsTop() {
        return y + h - PAD - ROW_H * ROWS - LINE_H;
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
        for (int row = 0; row < ROWS; row++) {
            boolean picked = row == selected;
            if (picked) {
                s.fillRoundRect(ix - 2, ry - 1, iw + 4, ROW_H, NumenStyle.RADIUS_SMALL, c.selected());
            }
            int textY = ry + (ROW_H - s.lineHeight()) / 2;
            String head = (row + 1) + "  " + label(row);
            boolean refusing = row >= CHOICES.length - 1;
            s.drawText(TextClip.fit(s, head, iw), ix, textY,
                    refusing ? c.danger() : picked ? c.textPrimary() : c.textSecondary(), false);
            String scope = switch (row) {
                case 0 -> I18n.get(ModLanguageData.Keys.CONSENT_ALLOW_SCOPE);
                case 1 -> String.join("; ", request.remember());
                default -> "";
            };
            int room = iw - s.textWidth(head) - 8;
            if (!scope.isEmpty() && room > 0) {
                String fitted = TextClip.fit(s, scope, room);
                s.drawText(fitted, ix + iw - s.textWidth(fitted), textY, c.textMuted(), false);
            }
            ry += ROW_H;
        }
        s.drawText(TextClip.fit(s, I18n.get(ModLanguageData.Keys.CONSENT_KEYS), iw), ix, ry, c.textMuted(), false);
    }
}
