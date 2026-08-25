package com.dwinovo.numen.client.hud;

import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.NumenToasts;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 游戏内 HUD 的 toast 宿主。玩家活在面板外(Y/V 快捷对话是对话正门),
 * 请求挂了的那一刻多半没开任何界面——HUD toast 是唯一能接住他的通道。
 * push 线程安全,异步失败回调直接投;渲染挂在两个 loader 的 HUD 层
 * (TalkHint 同层)。设置屏里的 toast 是各屏自己的实例,与这里互不相干。
 */
public final class NumenHudToasts {

    private static final NumenToasts TOASTS = new NumenToasts();

    private NumenHudToasts() {}

    public static void push(NumenToasts.Severity severity, String message) {
        TOASTS.push(severity, message);
    }

    /** loader 的 HUD 层每帧调用。 */
    public static void render(GuiGraphicsExtractor g) {
        if (TOASTS.isIdle()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden()) return;
        TOASTS.render(new McDrawSurface(g, mc.font),
                mc.getWindow().getGuiScaledWidth(),
                NumenTheme.DARK.colors(), Util.getMillis());
    }
}
