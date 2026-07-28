package com.dwinovo.numen.client.hud;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.screen.UiTheme;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 头顶气泡的渲染:同伴说的话浮在它自己头上,而不是弹在屏幕角落——话
 * 从人身上冒出来,它才是这个世界的居民。
 *
 * <p>从玩家实体渲染尾部进入(mixin,与名牌同一条管线):实体通道是
 * 光影/着色器正确处理的路径,世界渲染阶段的裸几何在 Iris 下会被管线
 * 吃掉只剩残影。几何一律挂在名牌同款的 {@code RenderType.text} 上
 * (白色底图 + 顶点着色),文字全亮度——夜里也得看得清她在说什么。
 *
 * <p>视觉沿用 GUI 的 BlockFrame 方言:方角、粗边、硬偏移阴影,配色取
 * 当前 {@link UiTheme}(奶油底深字),底部一枚小方尾指向说话者。思考
 * 态渲染成跳动的省略号。
 */
public final class SpeechBubbleRenderer {

    private static final ResourceLocation WHITE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/white.png");

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
                              MultiBufferSource buffers) {
        SpeechBubbles.Bubble bubble = SpeechBubbles.live(body.getUUID());
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
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(SCALE, -SCALE, SCALE);
        drawBubble(poseStack, buffers, mc.font, bubble);
        poseStack.popPose();
    }

    /**
     * 局部坐标:+y 朝下(朝说话者),气泡主体在 y∈[-boxH,0],小方尾从
     * 底边中央伸到 (0,TAIL_H)。层次靠 z 拉开(负 z 朝观察者):阴影最远、
     * 边框次之、填充、文字最前。
     */
    private static void drawBubble(PoseStack poseStack, MultiBufferSource buffers,
                                   Font font, SpeechBubbles.Bubble bubble) {
        UiTheme th = UiTheme.current();
        List<String> lines = bubble.thinking()
                ? List.of(thinkingDots())
                : wrapToWidth(font, bubble.text(), MAX_WIDTH, MAX_LINES);
        if (lines.isEmpty()) {
            return;
        }
        int textW = 0;
        for (String line : lines) {
            textW = Math.max(textW, font.width(line));
        }
        int boxW = textW + PAD_X * 2;
        int boxH = lines.size() * LINE_H + PAD_Y * 2;
        float x0 = -boxW / 2.0f;
        float x1 = boxW / 2.0f;
        float y0 = -boxH;
        float y1 = 0;

        VertexConsumer vc = buffers.getBuffer(RenderType.text(WHITE));
        Matrix4f m = poseStack.last().pose();
        // 硬偏移阴影(整体,含尾影由主影覆盖)
        quad(vc, m, x0 + SHADOW_OFF, y0 + SHADOW_OFF, x1 + SHADOW_OFF, y1 + SHADOW_OFF,
                0.10f, th.border());
        // 粗边:比填充大一圈的同心方
        quad(vc, m, x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0.06f, th.border());
        quad(vc, m, x0, y0, x1, y1, 0.03f, bubble.thinking() ? th.surface() : th.aiFill());
        // 小方尾:边框菱形在后,填充菱形在前,尖端指向说话者
        diamond(vc, m, 0, y1, 5, TAIL_H + 1, 0.06f, th.border());
        diamond(vc, m, 0, y1 - 1, 4, TAIL_H, 0.03f, bubble.thinking() ? th.surface() : th.aiFill());

        int color = bubble.thinking() ? th.textDim() : th.text();
        float ty = y0 + PAD_Y + 1;
        for (String line : lines) {
            float tx = -font.width(line) / 2.0f;
            font.drawInBatch(line, tx, ty, color, false, poseStack.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
            ty += LINE_H;
        }
    }

    /** 思考中:三点脉冲,约 0.4s 一跳。 */
    private static String thinkingDots() {
        int n = (int) (System.currentTimeMillis() / 400 % 3) + 1;
        return switch (n) {
            case 1 -> "·";
            case 2 -> "· ·";
            default -> "· · ·";
        };
    }

    private static void quad(VertexConsumer vc, Matrix4f m,
                             float x0, float y0, float x1, float y1, float z, int argb) {
        vc.addVertex(m, x0, y0, z).setColor(argb).setUv(0f, 0f).setLight(FULL_BRIGHT);
        vc.addVertex(m, x0, y1, z).setColor(argb).setUv(0f, 1f).setLight(FULL_BRIGHT);
        vc.addVertex(m, x1, y1, z).setColor(argb).setUv(1f, 1f).setLight(FULL_BRIGHT);
        vc.addVertex(m, x1, y0, z).setColor(argb).setUv(1f, 0f).setLight(FULL_BRIGHT);
    }

    /** 以 (cx, top) 为上顶点的下指菱形(方尾)。 */
    private static void diamond(VertexConsumer vc, Matrix4f m,
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
