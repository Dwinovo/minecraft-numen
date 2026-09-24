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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 头顶气泡的渲染:同伴说的话浮在它自己头上,而不是弹在屏幕角落——话
 * 从人身上冒出来,它才是这个世界的居民。
 *
 * <p>从玩家实体渲染尾部进入(mixin,与名牌同一条管线):实体通道是
 * 光影/着色器正确处理的路径,世界渲染阶段的裸几何在 Iris 下会被管线
 * 吃掉只剩残影。几何一律挂在名牌同款的 {@code RenderType.text} 上
 * (白色底图 + 顶点着色),文字全亮度——夜里也得看得清她在说什么。
 *
 * <p>配色与层次照 Telegram 她那一侧的气泡,形状是 MC 的方角:底色是她的气泡色({@code aiFill}),
 * 不描边,底下一道一像素的影(Telegram 的 msgInShadow,取分隔线色);底部一枚小方尾指向说话者。
 *
 * <p>一只气泡里可以同时有两条线(见 {@link SpeechBubbles}):正文在上、状态行在下——它是旁白,
 * 不是她说的话。正在做什么、等你点头用强调色文字(Telegram 里"正在输入"那一档);只是在想,用气泡里
 * 时间戳那一档淡字。只剩状态行时底色退成纸面,一眼能分出"她在说话"还是"她在忙"。
 *
 * <p>不硬切:气泡出现时淡入,话变长变短时框的宽高缓动追过去,正文刚说出口淡入、到点前淡出,
 * 整只气泡没了之后按最后一眼的样子淡出。
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
    private static final int TAIL_H = 5;          // 底部小方尾
    private static final double VIEW_RANGE_SQ = 48.0 * 48.0;
    private static final int FULL_BRIGHT = 0xF000F0;
    /** 出现、消失的时长(Telegram 的快动效);框宽高的趋近速率。 */
    private static final float FADE_MS = 150f;
    private static final float SIZE_RATE = 16f;
    /** 隔这么久没画过(走远了、换了维度)就当重新出现,不接着上一次的动画。 */
    private static final long STALE_NS = 250_000_000L;

    /** 一只同伴头顶气泡此刻画到哪了。 */
    private static final class Shown {
        float w, h;
        /** 出现进度 0..1;消失进度 0..1(她头上已经没东西了,按 {@link #last} 的样子淡出)。 */
        float appear, leaving;
        /** 底色从纸面(纯状态)到她的气泡色(有话)的过渡 0..1。 */
        float talking;
        SpeechBubbles.View last;
        long lastNanos;
    }

    private static final Map<UUID, Shown> SHOWN = new HashMap<>();

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
        UUID uuid = body.getUUID();
        SpeechBubbles.View view = SpeechBubbles.view(uuid);
        Minecraft mc = Minecraft.getInstance();
        if (body.isInvisible() || mc.getEntityRenderDispatcher().distanceToSqr(body) > VIEW_RANGE_SQ) {
            SHOWN.remove(uuid);
            return;
        }
        long nowNs = System.nanoTime();
        Shown st = SHOWN.get(uuid);
        if (st != null && nowNs - st.lastNanos > STALE_NS) {
            SHOWN.remove(uuid);
            st = null;
        }
        if (view == null && st == null) {
            return;
        }
        if (st == null) {
            st = new Shown();
            st.lastNanos = nowNs;
            SHOWN.put(uuid, st);
        }
        float dtMs = (nowNs - st.lastNanos) / 1.0e6f;
        st.lastNanos = nowNs;
        if (view != null) {
            st.last = view;
            st.leaving = 0f;
            st.appear = Math.min(1f, st.appear + dtMs / FADE_MS);
        } else {
            st.leaving = Math.min(1f, st.leaving + dtMs / FADE_MS);
            if (st.leaving >= 1f) {
                SHOWN.remove(uuid);
                return;
            }
        }
        poseStack.pushPose();
        // 锚点在名牌上方:小方尾的尖端落在这里,气泡向上生长
        poseStack.translate(0, body.getBbHeight() + 0.95, 0);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(SCALE, -SCALE, SCALE);
        drawBubble(poseStack, buffers, mc.font, st, dtMs / 1000f);
        poseStack.popPose();
    }

    /**
     * 局部坐标:+y 朝下(朝说话者),气泡主体在 y∈[-boxH,0],小方尾从
     * 底边中央伸到 (0,TAIL_H)。层次靠 z 拉开——名牌 billboard 空间里
     * <b>+z 朝观察者</b>:影垫底(0)、填充抬高,文字最前。
     */
    private static void drawBubble(PoseStack poseStack, MultiBufferSource buffers,
                                   Font font, Shown st, float dt) {
        SpeechBubbles.View bubble = st.last;
        UiTheme th = UiTheme.current();
        // 两条线各自成行:正文在上(她说的话),状态在下(此刻在干什么)。
        List<String> lines = new ArrayList<>();
        if (bubble.hasText()) {
            lines.addAll(wrapToWidth(font, bubble.text(), MAX_WIDTH, MAX_LINES));
        }
        // 从这行起是状态行:换一种颜色 + 前缀记号。"她在说话"和"她在干活"是两种东西,得看得出来。
        int statusFrom = lines.size();
        if (bubble.asking()) {
            lines.add(I18n.get("numen.bubble.asking"));
        } else if (bubble.hasStatus()) {
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
        // 框的宽高缓动追目标:话变长变短、状态行来去都不跳。刚出现时直接就位,只淡入
        float targetW = textW + PAD_X * 2;
        float targetH = lines.size() * LINE_H + PAD_Y * 2;
        if (st.w == 0f) {
            st.w = targetW;
            st.h = targetH;
            st.talking = bubble.hasText() ? 1f : 0f;
        } else if (st.leaving == 0f) {
            st.w = com.dwinovo.numen.client.ui.Anim.approach(st.w, targetW, SIZE_RATE, dt);
            st.h = com.dwinovo.numen.client.ui.Anim.approach(st.h, targetH, SIZE_RATE, dt);
        }
        // 整只气泡的出场程度:出现淡入、消失淡出;只有正文时跟着正文到点一起淡(话到点了,气泡也就走了)。
        // 换一句话不淡整只气泡——只有新的那句淡入,框缓动到新的大小
        float base = st.appear * (1f - st.leaving);
        float alpha = base * (bubble.hasStatus() ? 1f : bubble.textOut());
        if (alpha < 0.05f) {
            return;
        }
        float x0 = -Math.round(st.w) / 2.0f;
        float x1 = Math.round(st.w) / 2.0f;
        float y0 = -Math.round(st.h);
        float y1 = 0;

        VertexConsumer vc = buffers.getBuffer(RenderType.text(WHITE));
        Matrix4f m = poseStack.last().pose();
        // 有话说就是她的气泡色,纯状态退成纸面(退后一档);两者之间过渡,不跳
        if (st.leaving == 0f) {
            st.talking = Math.clamp(st.talking + (bubble.hasText() ? dt : -dt) * 1000f / FADE_MS, 0f, 1f);
        }
        int fill = fade(UiTheme.mix(th.surface(), th.aiFill(), st.talking), alpha);
        int shadow = fade(th.border(), alpha);
        // 底下一道一像素的影(Telegram 的 msgInShadow),方尾跟着往下错一像素
        quad(vc, m, x0, y1, x1, y1 + 1, 0.0f, shadow);
        diamond(vc, m, 0, y1, 4, TAIL_H, 0.0f, shadow);
        quad(vc, m, x0, y0, x1, y1, 0.04f, fill);
        diamond(vc, m, 0, y1 - 1, 4, TAIL_H, 0.04f, fill);

        // 文字压最前(drawInBatch 没有 z 参,用矩阵抬)
        poseStack.pushPose();
        poseStack.translate(0, 0, 0.06f);
        float ty = y0 + PAD_Y + 1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            // 正文用正文色,跟着正文一起淡入淡出;正在做什么、等你点头用强调色文字;只是在想用气泡里的淡字
            int color = i < statusFrom
                    ? fade(th.text(), base * bubble.textIn() * bubble.textOut())
                    : fade(bubble.asking() || bubble.activity() != null ? th.accent() : th.inMeta(), alpha);
            float tx = -font.width(line) / 2.0f;
            font.drawInBatch(line, tx, ty, color, false, poseStack.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
            ty += LINE_H;
        }
        poseStack.popPose();
    }

    /** 颜色乘上出场程度。字的透明度低于 4/255 会被字体当成不透明,所以最低压在 0.05。 */
    private static int fade(int argb, float alpha) {
        int a = Math.round((argb >>> 24) * Math.max(0.05f, Math.min(1f, alpha)));
        return (a << 24) | (argb & 0xFFFFFF);
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
