package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.agent.goal.GoalPrompts;
import com.dwinovo.numen.agent.goal.GoalState;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.mc.Sprites;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.FormattedCharSequence;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 置顶条里的目标(Telegram 的置顶消息):抬头下面那条只放她的长期目标;点 × 本局收起,换了新目标再出现;
 * 点条本身往下展开目标详情——全文、第几轮与用时。
 *
 * <p>"换了新目标"按目标对象认:同一个目标活着时她那条循环一直给同一个对象,新定一个就是新对象,
 * 所以收起记的是对象本身(弱引用,目标清掉就跟着没了);重进游戏读盘回来的是新对象,又会置顶。
 */
public final class PinnedGoal {

    private static final int LINE_H = 10;
    private static final int PAD = 7;

    /** 本局收起过的目标。 */
    private static final Set<GoalState> DISMISSED = Collections.newSetFromMap(new WeakHashMap<>());

    private PinnedGoal() {}

    /** 主人点了 ×:这个目标本局不再置顶。 */
    public static void dismiss(GoalState goal) {
        DISMISSED.add(goal);
    }

    public static boolean dismissed(GoalState goal) {
        return DISMISSED.contains(goal);
    }

    /**
     * 展开态:从 {@code top} 往下长、盖在对话流上;高度贴内容,底不过 {@code bottom}。旗子、目标全文、
     * 第几轮与用时(淡字)。
     *
     * @param shownH 这一帧露出多高(展开/收起的过渡由宿主按帧推进);超出内容高按内容高算
     * @return 内容的完整高度——宿主拿它当过渡的目标
     */
    public static int renderOpen(GuiGraphics g, Font font, GoalState goal, int x, int w, int top, int bottom,
                                 int shownH, long nowMs) {
        UiTheme th = UiTheme.current();
        int ix = x + PAD + 12;
        int iw = w - PAD * 2 - 12;
        List<FormattedCharSequence> lines = font.split(Nb.colored(goal.objective(), th.text()), iw);
        String meta = I18n.get("numen.pin.goal_meta", goal.turnsExecuted(), GoalPrompts.elapsed(goal.elapsedMs(nowMs)));
        // 先量后画:框贴内容,底不过 bottom
        int fullH = Math.min(bottom - top, PAD + (lines.size() + 1) * LINE_H + PAD - 2);
        int h = Math.min(fullH, shownH);
        if (h <= 0) {
            return fullH;
        }
        // 过渡时只露上面这一截:框从置顶条往下长,里面的行跟着框的底边一起露出来
        g.enableScissor(x, top, x + w, top + h);
        NumenStyle.box(new McDrawSurface(g, font), x, top, w, fullH, th.band(), th.aiBorder());
        int ly = top + PAD;
        int end = top + fullH - PAD + 2;
        Sprites.draw(g, Sprites.FLAG, x + PAD - 3, ly - 1, Sprites.SIZE, th.cta());
        // 放不下全文时让出最后一行给轮次与用时
        for (FormattedCharSequence seq : lines) {
            if (ly + LINE_H * 2 > end) break;
            Nb.text(g, font, seq, ix, ly);
            ly += LINE_H;
        }
        Nb.text(g, font, meta, ix, ly, th.faint());
        g.disableScissor();
        return fullH;
    }
}
