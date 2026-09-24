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
    /** 抬头右端的 ⋮(更多),以及它菜单里的查看资料、查看群资料、邀请。 */
    public static final ResourceLocation MORE = icon("icon_more");
    public static final ResourceLocation USER = icon("icon_user");
    public static final ResourceLocation USERS = icon("icon_users");
    public static final ResourceLocation USER_PLUS = icon("icon_user_plus");
    /** ☰ 菜单里的设置、夜间模式(现在是亮的给月亮,暗的给太阳)。 */
    public static final ResourceLocation SETTINGS = icon("icon_settings");
    public static final ResourceLocation MOON = icon("icon_moon");
    public static final ResourceLocation SUN = icon("icon_sun");
    /** 左栏顶上搜索框里的放大镜。 */
    public static final ResourceLocation SEARCH = icon("icon_search");
    /** 设置首页每个分区的图标:模型、工具扩展、外接大脑、技能、人设、声线、皮肤、主题(语音输入用 MIC)。 */
    public static final ResourceLocation CPU = icon("icon_cpu");
    public static final ResourceLocation PLUG = icon("icon_plug");
    public static final ResourceLocation ROBOT = icon("icon_robot");
    public static final ResourceLocation BOOK = icon("icon_book");
    public static final ResourceLocation PERSONA = icon("icon_persona");
    public static final ResourceLocation VOLUME = icon("icon_volume");
    public static final ResourceLocation SHIRT = icon("icon_shirt");
    public static final ResourceLocation BRUSH = icon("icon_brush");
    /** 资料页:在哪、背包那一行(简介用 PERSONA,她记得的事用 BOOK)。 */
    public static final ResourceLocation MAP_PIN = icon("icon_map_pin");
    public static final ResourceLocation BACKPACK = icon("icon_backpack");
    /** 资料页的操作块:发消息。 */
    public static final ResourceLocation MESSAGE = icon("icon_message");
    /** 消息右键菜单里的引用回复。 */
    public static final ResourceLocation REPLY = icon("icon_reply");
    /** 左栏行的右键菜单:置顶(置顶的行右端也挂这枚)、标为已读。 */
    public static final ResourceLocation PIN = icon("icon_pin");
    public static final ResourceLocation READ = icon("icon_read");
    /** 群成员右键菜单里的移出。 */
    public static final ResourceLocation USER_X = icon("icon_user_x");
    /** 左栏顶上的 ☰(设置)与设置页抬头的 ←(回到对话)。 */
    public static final ResourceLocation MENU = icon("icon_menu");
    public static final ResourceLocation BACK = icon("icon_back");
    /** 置顶条展开后目标那一行的旗子。 */
    public static final ResourceLocation FLAG = icon("icon_flag");
    /** 会话分组:左栏行菜单里的"加入分组…"、分组菜单里的新建分组。 */
    public static final ResourceLocation FOLDER = icon("icon_folder");
    public static final ResourceLocation FOLDER_PLUS = icon("icon_folder_plus");
    /** 勾:分组菜单里已经在的那个分组前、设置条目库里当前同伴在用的那条行尾(强调色),右键菜单里"给她用"也是它。 */
    public static final ResourceLocation CHECK = icon("icon_check");
    /** 征询的"说一句再拒绝":输入框上方那条提示栏的图标。 */
    public static final ResourceLocation CANCEL = icon("icon_cancel");

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
