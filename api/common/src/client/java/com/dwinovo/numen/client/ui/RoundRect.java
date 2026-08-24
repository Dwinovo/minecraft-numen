package com.dwinovo.numen.client.ui;

import com.dwinovo.numen.Constants;
import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Anti-aliased rounded-rectangle fill via a tiny SDF core shader
 * ({@code assets/numen_api/shaders/core/rendertype_round_rect.vsh/.fsh}).
 * 1.21.5 render pipeline: shader programs are code-defined {@link RenderPipeline}s
 * (the 1.21.2–1.21.4 JSON shader configs are gone); the GLSL sources are looked up
 * from the resource tree by the locations given to the builder, and the pipeline is
 * compiled lazily on first use — no loader-side registration at all. Custom uniforms
 * are only settable on a raw {@link RenderPass}, so each fill flushes the GUI batch
 * and issues its own pass against the main render target (GUI drawing is still
 * immediate-mode on 1.21.5). If the pipeline fails to compile (a pack replaced the
 * GLSL with garbage) every call degrades to a plain square fill, so the GUI never
 * breaks — it just loses its corners.
 */
public final class RoundRect {

    /** 管线定义:着色器位置指 {@code assets/numen_api/shaders/core/rendertype_round_rect.*}。 */
    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "pipeline/round_rect"))
            .withVertexShader(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "core/rendertype_round_rect"))
            .withFragmentShader(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "core/rendertype_round_rect"))
            .withUniform("ModelViewMat", UniformType.MATRIX4X4)
            .withUniform("ProjMat", UniformType.MATRIX4X4)
            .withUniform("ColorModulator", UniformType.VEC4)
            .withUniform("u_Rect", UniformType.VEC4)
            .withUniform("u_Radius", UniformType.FLOAT)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withCull(false)
            .build();

    /** 复用的 4 顶点 VBO(POSITION_COLOR 每顶点 16 字节);GL 后端命令即刻执行,写-画-写-画安全。 */
    private static GpuBuffer vertexBuffer;

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
        // 编译失败(资源包换了坏 GLSL)→ 降级方角。getOrCompile 有缓存,重复调用只是查表。
        if (!RenderSystem.getDevice().precompilePipeline(PIPELINE).isValid()) {
            g.fill(x1, y1, x2, y2, argb);
            return;
        }
        g.flush();

        Matrix4f pose = g.pose().last().pose();
        // u_Rect must be in the same space as the baked vertex positions (pose is translation-only here)
        Vector4f center = pose.transform(new Vector4f((x1 + x2) / 2f, (y1 + y2) / 2f, 0f, 1f));

        float a = (argb >>> 24) / 255f;
        float r = (argb >> 16 & 0xFF) / 255f;
        float gr = (argb >> 8 & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;

        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(pose, x1, y1, 0).setColor(r, gr, b, a);
        bb.addVertex(pose, x1, y2, 0).setColor(r, gr, b, a);
        bb.addVertex(pose, x2, y2, 0).setColor(r, gr, b, a);
        bb.addVertex(pose, x2, y1, 0).setColor(r, gr, b, a);

        try (MeshData mesh = bb.buildOrThrow()) {
            if (vertexBuffer == null || vertexBuffer.isClosed()) {
                vertexBuffer = RenderSystem.getDevice().createBuffer(
                        () -> "numen_api round rect vertices", BufferType.VERTICES, BufferUsage.STREAM_WRITE,
                        mesh.vertexBuffer());
            } else {
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(vertexBuffer, mesh.vertexBuffer(), 0);
            }
        }

        RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indexBuffer = sequential.getBuffer(6);

        var target = Minecraft.getInstance().getMainRenderTarget();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                target.getColorTexture(), OptionalInt.empty(),
                target.getDepthTexture(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("ModelViewMat", RenderSystem.getModelViewMatrix());
            pass.setUniform("ProjMat", RenderSystem.getProjectionMatrix());
            pass.setUniform("ColorModulator", 1f, 1f, 1f, 1f);
            pass.setUniform("u_Rect", center.x(), center.y(), (x2 - x1) / 2f, (y2 - y1) / 2f);
            pass.setUniform("u_Radius", radius);
            if (RenderSystem.SCISSOR_STATE.isEnabled()) {
                pass.enableScissor(RenderSystem.SCISSOR_STATE);
            }
            pass.setVertexBuffer(0, vertexBuffer);
            pass.setIndexBuffer(indexBuffer, sequential.type());
            pass.drawIndexed(0, 6);
        }
    }
}
