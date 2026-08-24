package com.dwinovo.numen.client.ui;

import com.dwinovo.numen.Constants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderProgram;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Anti-aliased rounded-rectangle fill via a tiny SDF core shader
 * ({@code assets/numen_api/shaders/core/rendertype_round_rect.json}). 1.21.2+
 * shader pipeline: the program is a {@link ShaderProgram} KEY compiled by the
 * ShaderManager on resource load — it SCANS every {@code shaders/} config in the
 * resource tree, so no loader-side registration is needed at all (NeoForge's
 * RegisterShadersEvent is used only as a startup preload hint). We look the
 * compiled instance up by key per draw. While it's absent (load failure, or a
 * pack replaced it with garbage) every call degrades to a plain square fill, so
 * the GUI never breaks — it just loses its corners.
 */
public final class RoundRect {

    /** 程序键:configId 带 core/ 前缀——ShaderManager 按 {@code shaders/<path>.json} 找配置。 */
    public static final ShaderProgram PROGRAM = new ShaderProgram(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "core/rendertype_round_rect"),
            DefaultVertexFormat.POSITION_COLOR, ShaderDefines.EMPTY);

    private RoundRect() {}

    /** A bordered card: 1px border colour ring + inset body fill, same corner family. */
    public static void card(GuiGraphics g, int x1, int y1, int x2, int y2, float radius, int fill, int border) {
        fill(g, x1, y1, x2, y2, radius, border);
        fill(g, x1 + 1, y1 + 1, x2 - 1, y2 - 1, Math.max(0f, radius - 1f), fill);
    }

    public static void fill(GuiGraphics g, int x1, int y1, int x2, int y2, float radius, int argb) {
        radius = Math.min(radius, Math.min(x2 - x1, y2 - y1) / 2f);
        if (radius <= 0) {
            g.fill(x1, y1, x2, y2, argb);
            return;
        }
        g.flush();

        // 键查表取编译实例并设为当前(ShaderManager 已在资源重载时编译了资源树里
        // 的全部 shader 配置);加载失败返回 null → 降级方角。
        CompiledShaderProgram sh = RenderSystem.setShader(PROGRAM);
        if (sh == null) {
            g.fill(x1, y1, x2, y2, argb);
            return;
        }

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
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(pose, x1, y1, 0).setColor(r, gr, b, a);
        bb.addVertex(pose, x1, y2, 0).setColor(r, gr, b, a);
        bb.addVertex(pose, x2, y2, 0).setColor(r, gr, b, a);
        bb.addVertex(pose, x2, y1, 0).setColor(r, gr, b, a);
        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.disableBlend();
    }
}
