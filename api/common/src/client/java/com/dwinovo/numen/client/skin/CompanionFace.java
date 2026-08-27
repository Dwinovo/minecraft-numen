package com.dwinovo.numen.client.skin;

import com.dwinovo.numen.api.CompanionPortrait;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 画一个同伴的头像——引擎里<b>唯一</b>做"用插件的还是用皮肤脸"这个判断的地方。
 *
 * <p>名册、轮盘、聊天气泡、HUD、设置页各处都调这里。判断只写一次,就不会出现某一处
 * 忘了问插件、于是同一个同伴在两个界面里长得不一样。
 *
 * @see CompanionPortrait 插件那一侧的契约
 */
public final class CompanionFace {

    private static final List<CompanionPortrait> PROVIDERS = new CopyOnWriteArrayList<>();

    private CompanionFace() {}

    /** 插件注册入口,见 {@code NumenGateway#registerPortrait}。 */
    public static void register(CompanionPortrait provider) {
        if (provider != null) PROVIDERS.add(provider);
    }

    /**
     * 画一个<b>同伴</b>的头像。有插件认领就用它的方形纹理,没有就回退到皮肤脸。
     *
     * <p>只对同伴用。主人自己的头像、皮肤库条目的预览不走这里——那些不是同伴,
     * 让改外观的插件去接管它们是错的。
     *
     * @param skin 回退用的皮肤(引擎一直拿得到,所以回退永远可用)。
     *             1.20.1 的皮肤就是贴图位置,PlayerSkin 记录是 1.20.2+ 的
     */
    public static void draw(GuiGraphics g, UUID companion, ResourceLocation skin,
                            int x, int y, int size) {
        if (drawnByPlugin(g, companion, x, y, size)) return;
        PlayerFaceRenderer.draw(g, skin, x, y, size);
    }

    /** 按注册顺序问,第一个认领的胜出。 */
    private static boolean drawnByPlugin(GuiGraphics g, UUID companion, int x, int y, int size) {
        for (CompanionPortrait p : PROVIDERS) {
            try {
                if (p.draw(g, companion, x, y, size)) return true;
            } catch (RuntimeException ignored) {
                // 插件画头像出错不该把整个界面拖下水,当它没认领
            }
        }
        return false;
    }
}
