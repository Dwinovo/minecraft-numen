package com.dwinovo.numen.client.hud;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.screen.UiTheme;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.language.I18n;
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
 * 当前 {@link UiTheme}(奶油底深字),底部一枚小方尾指向说话者。
 *
 * <p>一只气泡里可以同时有两条线(见 {@link SpeechBubbles}):正文在上、
 * 状态行(正在 xxx / 正在思考中)在下且暗一档——它是旁白,不是她说的话。
 * 只剩状态行时底色退成纸面,一眼能分出"她在说话"还是"她在忙"。
 */
public final class SpeechBubbleRenderer {

    private static final ResourceLocation WHITE =
            new ResourceLocation(Constants.MOD_ID, "textures/gui/white.png");

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
        // 崩溃护栏:实体渲染通道里的异常会带走整个渲染线程——这里绝不外抛
        com.dwinovo.numen.client.ui.SafeUi.run("speech-bubble",
                () -> renderInner(body, poseStack, buffers));
    }

    private static void renderInner(AbstractClientPlayer body, PoseStack poseStack,
                                    MultiBufferSource buffers) {
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
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        // X 与 Y 同时取反:与本代名牌(renderNameTag 的 scale(-0.025, -0.025, 0.025))同一套
        // 手性。只翻 Y 会让行列式为负——整个空间被镜像,所有面的绕序随之翻转而被背面剔除
        // (自己的方块可以双面画糊过去,原版画的字形不能,结果就是"有框没字")。
        poseStack.scale(-SCALE, -SCALE, SCALE);
        drawBubble(poseStack, buffers, mc.font, bubble, body.getUUID());
        poseStack.popPose();
    }

    /**
     * 局部坐标:+y 朝下(朝说话者),气泡主体在 y∈[-boxH,0],小方尾从
     * 底边中央伸到 (0,TAIL_H)。层次靠 z 拉开——名牌 billboard 空间里
     * <b>+z 朝屏幕里</b>:阴影垫底(0),边框、填充、文字逐层往负 z 压,越靠前越负。
     */
    private static void drawBubble(PoseStack poseStack, MultiBufferSource buffers,
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
        float x0 = -boxW / 2.0f;
        float x1 = boxW / 2.0f;
        float y0 = -boxH;
        float y1 = 0;

        VertexConsumer vc = buffers.getBuffer(RenderType.text(WHITE));
        Matrix4f m = poseStack.last().pose();
        // 硬偏移阴影垫底(整体,含尾影由主影覆盖)
        quad(vc, m, x0 + SHADOW_OFF, y0 + SHADOW_OFF, x1 + SHADOW_OFF, y1 + SHADOW_OFF,
                0.0f, th.border());
        // 粗边:比填充大一圈的同心方
        quad(vc, m, x0 - 1, y0 - 1, x1 + 1, y1 + 1, -0.02f, th.border());
        // 有话说就是"说话泡"(奶油底),纯状态是"状态泡"(纸面底,退后一档)
        int fill = bubble.hasText() ? th.aiFill() : th.surface();
        quad(vc, m, x0, y0, x1, y1, -0.04f, fill);
        // 小方尾:边框菱形在后,填充菱形在前,尖端指向说话者
        diamond(vc, m, 0, y1, 5, TAIL_H + 1, -0.02f, th.border());
        diamond(vc, m, 0, y1 - 1, 4, TAIL_H, -0.04f, fill);

        // 文字压最前(drawInBatch 没有 z 参,用矩阵抬)
        poseStack.pushPose();
        poseStack.translate(0, 0, -0.06f);
        float ty = y0 + PAD_Y + 1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            // 状态行暗一档:它是旁白,正文才是她说的话
            int color = i >= statusFrom ? th.textDim() : th.text();
            float tx = -font.width(line) / 2.0f;
            font.drawInBatch(line, tx, ty, color, false, poseStack.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
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

    private static void quad(VertexConsumer vc, Matrix4f m,
                             float x0, float y0, float x1, float y1, float z, int argb) {
        // 这一代顶点是「链式设值 + endVertex 提交」,顺序必须跟顶点格式一致:
        // pos → color → uv → light。
        vc.vertex(m, x0, y0, z).color(argb).uv(0f, 0f).uv2(FULL_BRIGHT).endVertex();
        vc.vertex(m, x0, y1, z).color(argb).uv(0f, 1f).uv2(FULL_BRIGHT).endVertex();
        vc.vertex(m, x1, y1, z).color(argb).uv(1f, 1f).uv2(FULL_BRIGHT).endVertex();
        vc.vertex(m, x1, y0, z).color(argb).uv(1f, 0f).uv2(FULL_BRIGHT).endVertex();
    }

    /** 以 (cx, top) 为上顶点的下指菱形(方尾)。 */
    private static void diamond(VertexConsumer vc, Matrix4f m,
                                float cx, float top, float halfW, float h, float z, int argb) {
        vc.vertex(m, cx - halfW, top, z).color(argb).uv(0f, 0f).uv2(FULL_BRIGHT).endVertex();
        vc.vertex(m, cx, top + h, z).color(argb).uv(0f, 1f).uv2(FULL_BRIGHT).endVertex();
        vc.vertex(m, cx + halfW, top, z).color(argb).uv(1f, 1f).uv2(FULL_BRIGHT).endVertex();
        vc.vertex(m, cx, top - 1, z).color(argb).uv(1f, 0f).uv2(FULL_BRIGHT).endVertex();
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
