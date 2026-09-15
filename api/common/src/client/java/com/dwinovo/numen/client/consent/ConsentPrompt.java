package com.dwinovo.numen.client.consent;

import com.dwinovo.numen.client.agent.KnownSkins;
import com.dwinovo.numen.client.skin.CompanionFace;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextClip;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.Widget;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 答复框:她在等主人点头时取代整条输入行——G 面板和 Y 快捷对话是同一张(都由输入行开),和 pi 把编辑器整个换成
 * 选择框同一个做法,版式也照 pi 的选择框收:顶上一道线,一行抬头,几项选项,选中的那项前面一个箭头。
 *
 * <pre>
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  ← 撤不回时是红的
 * [脸] 小蓝 +1              ▰▰▰▰▱▱ 87 秒  ← 别的同伴还有几条在等;剩下的时间
 * 挖 [原木]×6 · dwinovo 放的              ← 清单:动词、图标(没有图标写名字)、数量、理由
 * → 1 允许
 *   2 以后都允许      allow break(…)       ← 记下的规则只在选中这项时出现
 *   3 拒绝
 *   4 ____________________________        ← 输入框:写一句再拒绝
 * </pre>
 *
 * <p>第四项就是一个输入框——选到它直接打字,回车按拒绝连同这句送出(和 pi 的 "Type something"、Claude Code 的
 * "否,并告诉它换个做法"同一个意思:主人要说点什么,就是不让她照原样做)。↑↓ 选、回车确定,或者直接按 1-4。
 * 清单里有撤不回的事时不给默认选中,必须主人自己挑。
 *
 * <p>第四项的输入框是输入行自己那一个(屏幕上始终只有一个真输入框),摆在 {@link #noteBox} 那一格、只画下划线,
 * 选中第四项时才接字。Esc 照常关界面,请求留着,再按对话键回来答。
 */
public final class ConsentPrompt extends Widget {

    /** 输入框摆放的那一格。 */
    public record Box(int x, int y, int w, int h) {}

    /** 清单最多列几堆,其余计数。 */
    private static final int MAX_LINES = 3;
    private static final int PAD = 4;
    private static final int HEAD_H = 14;
    private static final int LINE_H = 13;
    private static final int ROW_H = 12;
    private static final int NOTE_H = 14;
    private static final int FACE = 10;
    private static final int BAR_W = 36;
    /** 图标画成 12 像素(物品原图 16)。 */
    private static final float ICON_SCALE = 0.75f;
    private static final int ICON = 12;
    /** 行首箭头与序号那一截的宽度。 */
    private static final int MARK_W = 20;
    private static final String ARROW = "→";
    /** 前三项各是一种答复;第四项是写一句再拒绝。 */
    private static final ConsentAnswer.Decision[] CHOICES = {
            ConsentAnswer.Decision.ALLOW_ONCE, ConsentAnswer.Decision.ALLOW_REMEMBER, ConsentAnswer.Decision.DENY};
    private static final int NOTE_ROW = CHOICES.length;

    private final ConsentRequestPayload request;
    private final String name;
    private final boolean irreversible;
    /** 每堆的图标,建框时做好一次;没有图标的那堆是 null。 */
    private final List<ItemStack> icons = new ArrayList<>();
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
        this.name = ConsentCards.name(request.companion());
        this.irreversible = request.lines().stream().anyMatch(ConsentRequestPayload.Line::irreversible);
        this.selected = replaced != null && replaced.writing() ? NOTE_ROW : irreversible ? -1 : 0;
        for (ConsentRequestPayload.Line line : request.lines()) {
            icons.add(line.icon() == null ? null : new ItemStack(line.icon()));
        }
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
        return 1 + PAD + HEAD_H + LINE_H * listedLines() + 2 + ROW_H * CHOICES.length + NOTE_H + PAD;
    }

    /** 第四项输入框摆在哪。 */
    public Box noteBox() {
        return new Box(x + PAD + MARK_W, noteTop(), w - PAD * 2 - MARK_W, NOTE_H - 1);
    }

    private int listedLines() {
        return Math.min(MAX_LINES, request.lines().size()) + (request.lines().size() > MAX_LINES ? 1 : 0);
    }

    private int rowsTop() {
        return y + h - PAD - NOTE_H - ROW_H * CHOICES.length;
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

    /** 交出去;框由输入行在撤回之后收起,框没了就是答上了。 */
    private void answer(ConsentAnswer.Decision decision, String said) {
        if (answered) {
            return;
        }
        answered = true;
        ConsentCards.reply(request, decision, said);
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        int tone = irreversible ? c.danger() : c.accent();
        s.fillRect(x, y, w, h, c.panelBg());
        s.fillRect(x, y, w, 1, tone);
        int ix = x + PAD;
        int iw = w - PAD * 2;
        int ty = y + 1 + PAD;
        int textDy = (HEAD_H - s.lineHeight()) / 2;

        // 抬头:谁在问、别的同伴还有几条在等、还剩多少时间
        if (s instanceof McDrawSurface mc) {
            CompanionFace.draw(mc.graphics(), request.companion(), KnownSkins.of(request.companion()),
                    ix, ty + (HEAD_H - FACE) / 2, FACE);
        }
        int nx = ix + FACE + 4;
        String shownName = TextClip.fit(s, name, iw - FACE - 4 - BAR_W - 60);
        s.drawText(shownName, nx, ty + textDy, c.textPrimary(), false);
        int others = ConsentCards.all().size() - 1;
        if (others > 0) {
            s.drawText("+" + others, nx + s.textWidth(shownName) + 4, ty + textDy, c.textMuted(), false);
        }
        var level = Minecraft.getInstance().level;
        long left = level == null ? 0 : Math.max(0, request.expiresAtGameTime() - level.getGameTime());
        String seconds = I18n.get(ModLanguageData.Keys.CONSENT_SECONDS, (left + 19) / 20);
        int secondsW = s.textWidth(seconds);
        s.drawText(seconds, ix + iw - secondsW, ty + textDy, c.textMuted(), false);
        int barX = ix + iw - secondsW - 4 - BAR_W;
        int barY = ty + HEAD_H / 2 - 1;
        s.fillRect(barX, barY, BAR_W, 2, c.inputBorder());
        s.fillRect(barX, barY, (int) Math.round(BAR_W * Math.min(1.0, (double) left / ConsentDesk.TIMEOUT_TICKS)), 2,
                tone);
        ty += HEAD_H;

        // 清单:动词、图标(没有图标写名字)、数量、理由;撤不回的那堆整行警示色
        List<ConsentRequestPayload.Line> lines = request.lines();
        for (int i = 0; i < Math.min(MAX_LINES, lines.size()); i++) {
            drawLine(s, c, lines.get(i), icons.get(i), ix, ty + (LINE_H - s.lineHeight()) / 2, iw);
            ty += LINE_H;
        }
        if (lines.size() > MAX_LINES) {
            s.drawText("+" + (lines.size() - MAX_LINES), ix, ty + (LINE_H - s.lineHeight()) / 2, c.textMuted(), false);
        }

        // 选项:选中的那项前面一个箭头;记下的规则只在选中"以后都允许"时出现
        int ry = rowsTop();
        for (int row = 0; row < CHOICES.length; row++) {
            int textY = ry + (ROW_H - s.lineHeight()) / 2;
            drawMark(s, c, row, ix, textY);
            String label = label(CHOICES[row]);
            s.drawText(label, ix + MARK_W, textY, row == selected ? c.textPrimary() : c.textSecondary(), false);
            if (row == selected && CHOICES[row] == ConsentAnswer.Decision.ALLOW_REMEMBER) {
                int room = iw - MARK_W - s.textWidth(label) - 8;
                String rules = TextClip.fit(s, String.join("; ", request.remember()), room);
                s.drawText(rules, ix + iw - s.textWidth(rules), textY, c.textMuted(), false);
            }
            ry += ROW_H;
        }
        drawMark(s, c, NOTE_ROW, ix, ry + (NOTE_H - s.lineHeight()) / 2);
    }

    /** 行首:选中的是强调色箭头,后面是序号。 */
    private void drawMark(IDrawSurface s, NumenTheme.Colors c, int row, int ix, int textY) {
        if (row == selected) {
            s.drawText(ARROW, ix, textY, c.accent(), false);
        }
        s.drawText(String.valueOf(row + 1), ix + 10, textY, row == selected ? c.accent() : c.textMuted(), false);
    }

    private void drawLine(IDrawSurface s, NumenTheme.Colors c, ConsentRequestPayload.Line line, ItemStack icon,
                          int lx, int textY, int width) {
        int end = lx + width;
        int main = line.irreversible() ? c.danger() : c.textPrimary();
        int soft = line.irreversible() ? c.danger() : c.textMuted();
        String verb = I18n.get(ModLanguageData.Keys.CONSENT_VERB_PREFIX + line.kind().verb());
        s.drawText(verb, lx, textY, soft, false);
        lx += s.textWidth(verb) + 4;
        if (icon != null && s instanceof McDrawSurface mc) {
            var pose = mc.graphics().pose();
            pose.pushPose();
            pose.translate(lx, textY - 2, 0);
            pose.scale(ICON_SCALE, ICON_SCALE, 1f);
            mc.graphics().renderFakeItem(icon, 0, 0);
            pose.popPose();
            lx += ICON + 1;
        } else {
            String shown = line.name().getString();
            s.drawText(shown, lx, textY, main, false);
            lx += s.textWidth(shown);
        }
        if (line.count() > 1) {
            String count = "×" + line.count();
            s.drawText(count, lx, textY, main, false);
            lx += s.textWidth(count);
        }
        String cause = TextClip.fit(s, " · " + line.cause().getString(), end - lx);
        s.drawText(cause, lx, textY, soft, false);
    }
}
