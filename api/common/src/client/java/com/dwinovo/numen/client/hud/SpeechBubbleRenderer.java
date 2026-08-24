package com.dwinovo.numen.client.hud;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.screen.UiTheme;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 头顶气泡的渲染:同伴说的话浮在它自己头上,而不是弹在屏幕角落——话
 * 从人身上冒出来,它才是这个世界的居民。
 *
 * <p>从玩家实体渲染尾部进入(mixin,与名牌同一条管线):实体通道是
 * 光影/着色器正确处理的路径,世界渲染阶段的裸几何在 Iris 下会被管线
 * 吃掉只剩残影。几何一律挂在名牌同款的 {@code RenderTypes.text} 上
 * (白色底图 + 顶点着色),文字全亮度——夜里也得看得清她在说什么。
 *
 * <p>视觉沿用 GUI 的 BlockFrame 方言:方角、粗边、硬偏移阴影,配色取
 * 当前 {@link UiTheme}(奶油底深字),底部一枚小方尾指向说话者。
 *
 * <p>一只气泡里可以同时有两条线(见 {@link SpeechBubbles}):正文在上、
 * 状态行(正在 xxx / 正在思考中)在下且暗一档——它是旁白,不是她说的话。
 * 只剩状态行时底色退成纸面,一眼能分出"她在说话"还是"她在忙"。
 */
public final class SpeechBubbleRenderer {

    private static final Identifier WHITE =
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/white.png");

    private static final float SCALE = 0.025f;
    private static final int MAX_WIDTH = 130;     // 文本换行宽(px)
    private static final int MAX_LINES = 6;       // 超出省略——气泡是预览,全文在聊天栏
    private static final int LINE_H = 10;
    private static final int PAD_X = 5;
    private static final int PAD_Y = 4;
    private static final int SHADOW_OFF = 2;      // 硬阴影偏移(BlockFrame 方言)
    private static final int TAIL_H = 5;          // 底部小方尾
    private static final double VIEW_RANGE_SQ = 48.0 * 48.0;
    private static final int FULL_BRIGHT = 0xF000F0;

    private SpeechBubbleRenderer() {}

    /**
     * 实体渲染尾部入口(poseStack 原点在实体脚下,交给我们时是干净的)。
     * 没有气泡的实体(包括所有真人玩家)一次 map 查询即返回。
     */
    public static void render(AbstractClientPlayer body, PoseStack poseStack,
                              SubmitNodeCollector collector,
                              net.minecraft.client.renderer.state.CameraRenderState camera) {
        // 崩溃护栏:实体渲染通道里的异常会带走整个渲染线程——这里绝不外抛
        com.dwinovo.numen.client.ui.SafeUi.run("speech-bubble",
                () -> renderInner(body, poseStack, collector, camera));
    }

    private static void renderInner(AbstractClientPlayer body, PoseStack poseStack,
                                    SubmitNodeCollector collector,
                                    net.minecraft.client.renderer.state.CameraRenderState camera) {
        SpeechBubbles.View bubble = SpeechBubbles.view(body.getUUID());
        if (bubble == null || body.isInvisible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getEntityRenderDispatcher().distanceToSqr(body) > VIEW_RANGE_SQ) {
            return;
        }
        poseStack.pushPose();
        // 锚点在名牌上方:小方尾的尖端落在这里,气泡向上生长
        poseStack.translate(0, body.getBbHeight() + 0.95, 0);
        // 1.21.9+ 相机朝向从渲染状态里取(与原版名牌同一个来源)。
        poseStack.mulPose(camera.orientation);
        poseStack.scale(SCALE, -SCALE, SCALE);
        drawBubble(poseStack, collector, mc.font, bubble, body.getUUID());
        poseStack.popPose();
    }

    /**
     * 局部坐标:+y 朝下(朝说话者),气泡主体在 y∈[-boxH,0],小方尾从
     * 底边中央伸到 (0,TAIL_H)。层次靠 z 拉开——名牌 billboard 空间里
     * <b>+z 朝观察者</b>:阴影垫底(0)、边框、填充逐层抬高,文字最前。
     */
    private static void drawBubble(PoseStack poseStack, SubmitNodeCollector collector,
                                   Font font, SpeechBubbles.View bubble, java.util.UUID uuid) {
        UiTheme th = UiTheme.current();
        // 两条线各自成行:正文在上(她说的话),状态在下(此刻在干什么)。
        List<String> lines = new ArrayList<>();
        if (bubble.hasText()) {
            lines.addAll(wrapToWidth(font, bubble.text(), MAX_WIDTH, MAX_LINES));
        }
        // 从这行起是状态行:画暗一档 + 前缀记号。只差一档灰的话,主人读起来跟正文
        // 没区别——"她在说话"和"她在干活"是两种东西,得看得出来。
        int statusFrom = lines.size();
        if (bubble.hasStatus()) {
            lines.add(bubble.activity() != null
                    ? I18n.get("numen.bubble.doing", bubble.activity())
                    : I18n.get("numen.bubble.thinking") + thinkingDots());
        }
        if (lines.isEmpty()) {
            return;
        }
        int textW = 0;
        for (String line : lines) {
            textW = Math.max(textW, font.width(line));
        }
        int boxW = textW + PAD_X * 2;
        int boxH = lines.size() * LINE_H + PAD_Y * 2;
        final float x0 = -boxW / 2.0f;
        final float x1 = boxW / 2.0f;
        final float y0 = -boxH;
        final float y1 = 0;

        // 有话说就是"说话泡"(奶油底),纯状态是"状态泡"(纸面底,退后一档)
        final int fill = bubble.hasText() ? th.aiFill() : th.surface();
        final int border = th.border();
        // 1.21.9+ 提交式渲染:实体通道不再直取 VertexConsumer,几何交给
        // SubmitNodeCollector。一次 submitCustomGeometry 把整个气泡(阴影/边框/
        // 填充/小方尾)写进同一个节点,层次仍由 z 拉开;回调延后执行,
        // 参与计算的局部量全部 final。
        collector.submitCustomGeometry(poseStack, RenderTypes.text(WHITE), (pose, vc) -> {
            // 硬偏移阴影垫底(整体,含尾影由主影覆盖)
            quad(vc, pose, x0 + SHADOW_OFF, y0 + SHADOW_OFF, x1 + SHADOW_OFF, y1 + SHADOW_OFF,
                    0.0f, border);
            // 粗边:比填充大一圈的同心方
            quad(vc, pose, x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0.02f, border);
            quad(vc, pose, x0, y0, x1, y1, 0.04f, fill);
            // 小方尾:边框菱形在后,填充菱形在前,尖端指向说话者
            diamond(vc, pose, 0, y1, 5, TAIL_H + 1, 0.02f, border);
            diamond(vc, pose, 0, y1 - 1, 4, TAIL_H, 0.04f, fill);
        });

        // 文字压最前(submitText 没有 z 参,用矩阵抬)
        poseStack.pushPose();
        poseStack.translate(0, 0, 0.06f);
        float ty = y0 + PAD_Y + 1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            // 状态行暗一档:它是旁白,正文才是她说的话
            int color = i >= statusFrom ? th.textDim() : th.text();
            float tx = -font.width(line) / 2.0f;
            collector.submitText(poseStack, tx, ty,
                    FormattedCharSequence.forward(line, Style.EMPTY), false,
                    Font.DisplayMode.NORMAL, FULL_BRIGHT, color, 0, 0);
            ty += LINE_H;
        }
        poseStack.popPose();
    }

    /** 脉冲点:约 0.4s 一跳,证明她还活着。 */
    private static String thinkingDots() {
        return switch ((int) (System.currentTimeMillis() / 400 % 3)) {
            case 0 -> "·";
            case 1 -> "· ·";
            default -> "· · ·";
        };
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose m,
                             float x0, float y0, float x1, float y1, float z, int argb) {
        vc.addVertex(m, x0, y0, z).setColor(argb).setUv(0f, 0f).setLight(FULL_BRIGHT);
        vc.addVertex(m, x0, y1, z).setColor(argb).setUv(0f, 1f).setLight(FULL_BRIGHT);
        vc.addVertex(m, x1, y1, z).setColor(argb).setUv(1f, 1f).setLight(FULL_BRIGHT);
        vc.addVertex(m, x1, y0, z).setColor(argb).setUv(1f, 0f).setLight(FULL_BRIGHT);
    }

    /** 以 (cx, top) 为上顶点的下指菱形(方尾)。 */
    private static void diamond(VertexConsumer vc, PoseStack.Pose m,
                                float cx, float top, float halfW, float h, float z, int argb) {
        vc.addVertex(m, cx - halfW, top, z).setColor(argb).setUv(0f, 0f).setLight(FULL_BRIGHT);
        vc.addVertex(m, cx, top + h, z).setColor(argb).setUv(0f, 1f).setLight(FULL_BRIGHT);
        vc.addVertex(m, cx + halfW, top, z).setColor(argb).setUv(1f, 1f).setLight(FULL_BRIGHT);
        vc.addVertex(m, cx, top - 1, z).setColor(argb).setUv(1f, 0f).setLight(FULL_BRIGHT);
    }

    /** 逐像素贪心换行(CJK 友好),超行数截断并补省略号。 */
    private static List<String> wrapToWidth(Font font, String text, int maxW, int maxLines) {
        String s = text.replaceAll("\\s+", " ").trim();
        List<String> out = new ArrayList<>();
        while (!s.isEmpty() && out.size() < maxLines) {
            String head = font.plainSubstrByWidth(s, maxW);
            if (head.isEmpty()) {
                head = s.substring(0, 1);
            }
            out.add(head.trim());
            s = s.substring(head.length());
        }
        if (!s.isEmpty() && !out.isEmpty()) {
            String last = out.get(out.size() - 1);
            while (!last.isEmpty() && font.width(last + "…") > maxW) {
                last = last.substring(0, last.length() - 1);
            }
            out.set(out.size() - 1, last + "…");
        }
        return out;
    }
}
