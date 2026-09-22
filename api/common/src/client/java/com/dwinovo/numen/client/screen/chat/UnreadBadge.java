package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.client.screen.Nb;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 未读计数角标(Telegram):强调色小块、反色数字;左栏会话行的右端和"回到最新"钮顶上是同一枚。 */
public final class UnreadBadge {

    public static final int H = 11;

    private UnreadBadge() {}

    /** 三位以上就写 99+,角标不长。 */
    public static String label(int n) {
        return n > 99 ? "99+" : String.valueOf(n);
    }

    public static int width(Font font, String label) {
        return font.width(label) + 6;
    }

    public static void draw(GuiGraphics g, Font font, String label, int x, int y, int fill, int ink) {
        g.fill(x, y, x + width(font, label), y + H, fill);
        Nb.text(g, font, label, x + 3, y + 2, ink);
    }
}
