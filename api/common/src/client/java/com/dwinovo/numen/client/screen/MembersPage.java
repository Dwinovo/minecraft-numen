package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.client.agent.Conversations;
import com.dwinovo.numen.client.agent.KnownSkins;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.skin.CompanionFace;
import com.dwinovo.numen.client.ui.mc.Sprites;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 群资料页(Telegram 点群名打开的那页):标题行"N 位成员",右端 ＋ 邀请;下面成员一行一个——
 * 脸、名字、此刻在干什么。点一行开她的资料页;悬停时行尾出 ×,点它把她移出。只剩一个时没有 ×,
 * 那一步是解散,不在这里。
 */
final class MembersPage {

    /** 点中了什么:开谁的资料、移出谁、邀请。 */
    sealed interface Hit {
        record Open(UUID who) implements Hit {}
        record Drop(UUID who) implements Hit {}
        record Invite() implements Hit {}
    }

    private static final int HEAD_H = 22;
    private static final int ROW_H = 28;
    private static final int AV = 18;
    private static final int FACE_X = 4;

    private final Font font;
    private List<UUID> rows = List.of();
    private boolean canInvite;
    private int x, y, w;

    MembersPage(Font font) {
        this.font = font;
    }

    /** {@code live} = 这一页整页在场、没有模态压着:只有这时才亮悬停。{@code status} 给每只一行"在干什么"。 */
    void render(GuiGraphics g, Conversation conv, int x, int y, int w, int mouseX, int mouseY,
                boolean live, Function<UUID, String> status) {
        this.x = x;
        this.y = y;
        this.w = w;
        UiTheme t = UiTheme.current();
        rows = Conversations.instance().membersAlive(conv);
        canInvite = !Conversations.instance().pullable(conv).isEmpty();
        // 标题行用强调色(Telegram 分区标题的写法),＋ 贴右端
        Nb.text(g, font, I18n.get(ModLanguageData.Keys.HEADER_MEMBERS, rows.size()), x + FACE_X, y + 7, t.cta());
        if (canInvite) {
            boolean hot = live && overInvite(mouseX, mouseY);
            Sprites.draw(g, Sprites.USER_PLUS, plusX(), y + (HEAD_H - Sprites.SIZE) / 2, Sprites.SIZE,
                    hot ? t.cta() : t.textDim());
        }
        boolean droppable = rows.size() > 1;
        for (int i = 0; i < rows.size(); i++) {
            UUID m = rows.get(i);
            int ry = rowY(i);
            boolean hovered = live && mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H;
            if (hovered) g.fill(x, ry, x + w, ry + ROW_H, t.aiFill());
            CompanionFace.draw(g, m, KnownSkins.of(m), x + FACE_X, ry + (ROW_H - AV) / 2, AV);
            int tx = x + FACE_X + AV + 8;
            int room = (droppable ? dropX() - 4 : x + w - 4) - tx;
            Nb.text(g, font, Nb.clip(font, NumenRoster.instance().name(m), room), tx, ry + 5, t.text());
            Nb.text(g, font, Nb.clip(font, status.apply(m), room), tx, ry + 16, t.faint());
            if (hovered && droppable) {
                boolean overX = overDrop(i, mouseX, mouseY);
                Nb.text(g, font, "×", dropX(), ry + (ROW_H - font.lineHeight) / 2 + 1,
                        overX ? t.fail() : t.textDim());
            }
        }
    }

    /** 指针下该给的提示:× 是"移出谁",＋ 是"邀请";别处没有。 */
    String tipAt(double mx, double my) {
        if (canInvite && overInvite(mx, my)) return I18n.get(ModLanguageData.Keys.CONVO_INVITE);
        if (rows.size() > 1) {
            for (int i = 0; i < rows.size(); i++) {
                if (overDrop(i, mx, my)) {
                    return I18n.get(ModLanguageData.Keys.CONVO_DROP, NumenRoster.instance().name(rows.get(i)));
                }
            }
        }
        return null;
    }

    Hit click(double mx, double my) {
        if (canInvite && overInvite(mx, my)) return new Hit.Invite();
        for (int i = 0; i < rows.size(); i++) {
            int ry = rowY(i);
            if (mx < x || mx >= x + w || my < ry || my >= ry + ROW_H) continue;
            return rows.size() > 1 && overDrop(i, mx, my) ? new Hit.Drop(rows.get(i)) : new Hit.Open(rows.get(i));
        }
        return null;
    }

    private int rowY(int i) {
        return y + HEAD_H + i * ROW_H;
    }

    private int plusX() {
        return x + w - 4 - Sprites.SIZE;
    }

    private int dropX() {
        return x + w - 4 - font.width("×");
    }

    private boolean overInvite(double mx, double my) {
        int px = plusX(), py = y + (HEAD_H - Sprites.SIZE) / 2;
        return mx >= px - 3 && mx < px + Sprites.SIZE + 3 && my >= py - 3 && my < py + Sprites.SIZE + 3;
    }

    private boolean overDrop(int i, double mx, double my) {
        int ry = rowY(i);
        return mx >= dropX() - 4 && mx < x + w && my >= ry && my < ry + ROW_H;
    }
}
