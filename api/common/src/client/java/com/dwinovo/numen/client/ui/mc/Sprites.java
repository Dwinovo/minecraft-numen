package com.dwinovo.numen.client.ui.mc;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.widget.Button;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

/**
 * 界面里那些小图标:一张 12×12 的纯白像素图,按用处着色再贴。
 *
 * <p><b>为什么是贴图不是代码里的形状</b>:图标形状该由画图标的人来画。这里的几枚取自
 * pixelarticons(MIT),用 {@code api/tools/ui-textures/pixelarticons.py} 转成本项目的
 * png——它本来就是按 12×12 的像素格画的,转换不做重采样,拿到的就是作者那张稿子。
 * 授权与出处见仓库根的 {@code LICENSE-ASSETS}。
 *
 * <p><b>为什么图存白色</b>:白色乘以任何颜色就是那个颜色。常态、悬停、置灰、危险各是一次
 * 着色,不用为每种状态各存一张图。
 *
 * <p>{@code ui} 模块碰不到 MC 的资源系统,所以贴图这一步由本层注入:控件只管几何与状态色
 * (见 {@link Button#icon}),怎么把那块颜色画出来是这里的事。
 */
public final class Sprites {

    private Sprites() {}

    /** 图标的边长:与一行文字差不多高,放进 18px 的控件行里四周还留得下余量。 */
    public static final int SIZE = 12;

    public static final ResourceLocation COPY = icon("icon_copy");
    public static final ResourceLocation REFRESH = icon("icon_refresh");
    public static final ResourceLocation EDIT = icon("icon_edit");
    public static final ResourceLocation DELETE = icon("icon_delete");
    public static final ResourceLocation MIC = icon("icon_mic");
    public static final ResourceLocation SEND = icon("icon_send");
    public static final ResourceLocation STOP = icon("icon_stop");
    /** 抬头的「＋」(邀请),和铅笔、垃圾桶同一套。 */
    public static final ResourceLocation PLUS = icon("icon_plus");
    /** 状态行:她在忙、她的目标、她的计划。 */
    public static final ResourceLocation LOADER = icon("icon_loader");
    public static final ResourceLocation FLAG = icon("icon_flag");
    public static final ResourceLocation LIST = icon("icon_list");

    public static ResourceLocation icon(String name) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name);
    }

    /** 给 {@link Button#icon} 的画法:按控件给的状态色着色再贴。 */
    public static Button.IconDrawer painter(ResourceLocation sprite) {
        return painter(() -> sprite);
    }

    /** 图标随状态换的(录音中的麦克风换成停止方块):每帧现取。 */
    public static Button.IconDrawer painter(Supplier<ResourceLocation> sprite) {
        return (s, x, y, size, argb) -> {
            if (s instanceof McDrawSurface mc) {
                draw(mc.graphics(), sprite.get(), x, y, size, argb);
            }
        };
    }

    /** 直接贴一枚(不在控件里的那些,比如头部名字旁的改与删)。 */
    public static void draw(GuiGraphics g, ResourceLocation sprite, int x, int y, int size, int argb) {
        g.setColor((argb >> 16 & 0xFF) / 255f, (argb >> 8 & 0xFF) / 255f, (argb & 0xFF) / 255f,
                (argb >>> 24) / 255f);
        g.blitSprite(sprite, x, y, size, size);
        g.setColor(1f, 1f, 1f, 1f);
    }
}
