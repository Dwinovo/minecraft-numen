package com.dwinovo.numen.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Anti-aliased rounded-rectangle fill via a tiny SDF core shader
 * ({@code assets/numen_api/shaders/core/rendertype_round_rect}). The shader is
 * loader-registered (RegisterShadersEvent / CoreShaderRegistrationCallback) and
 * handed in through {@link #setShader}; while it's absent (load failure, or a
 * pack replaced it with garbage) every call degrades to a plain square fill, so
 * the GUI never breaks — it just loses its corners.
 */
public final class RoundRect {

    private static ShaderInstance shader;

    private RoundRect() {}

    /** Loader-side registration callback target. */
    public static void setShader(ShaderInstance s) {
        shader = s;
    }

    /** A bordered card: 1px border colour ring + inset body fill, same corner family. */
    public static void card(GuiGraphics g, int x1, int y1, int x2, int y2, float radius, int fill, int border) {
        fill(g, x1, y1, x2, y2, radius, border);
        fill(g, x1 + 1, y1 + 1, x2 - 1, y2 - 1, Math.max(0f, radius - 1f), fill);
    }

    public static void fill(GuiGraphics g, int x1, int y1, int x2, int y2, float radius, int argb) {
        ShaderInstance sh = shader;
        radius = Math.min(radius, Math.min(x2 - x1, y2 - y1) / 2f);
        if (sh == null || radius <= 0) {
            g.fill(x1, y1, x2, y2, argb);
            return;
        }
        g.flush();

        Matrix4f pose = g.pose().last().pose();
        // u_Rect must be in the same space as the baked vertex positions (pose is translation-only here)
        Vector4f center = pose.transform(new Vector4f((x1 + x2) / 2f, (y1 + y2) / 2f, 0f, 1f));
        sh.safeGetUniform("u_Rect").set(center.x(), center.y(), (x2 - x1) / 2f, (y2 - y1) / 2f);
        sh.safeGetUniform("u_Radius").set(radius);

        float a = (argb >>> 24) / 255f;
        float r = (argb >> 16 & 0xFF) / 255f;
        float gr = (argb >> 8 & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(() -> sh);
        // 1.20.1 顶点 API(getBuilder/begin/endVertex/end)——1.21 的 begin(...)/buildOrThrow 尚不存在。
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.vertex(pose, x1, y1, 0).color(r, gr, b, a).endVertex();
        bb.vertex(pose, x1, y2, 0).color(r, gr, b, a).endVertex();
        bb.vertex(pose, x2, y2, 0).color(r, gr, b, a).endVertex();
        bb.vertex(pose, x2, y1, 0).color(r, gr, b, a).endVertex();
        BufferUploader.drawWithShader(bb.end());
        RenderSystem.disableBlend();
    }
}
