package com.dwinovo.numen.client.ui;

import com.dwinovo.numen.Constants;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3x2f;
import org.jetbrains.annotations.Nullable;

/**
 * Anti-aliased rounded-rectangle fill via a tiny SDF core shader
 * ({@code assets/numen_api/shaders/core/rendertype_round_rect.vsh/.fsh}).
 * 1.21.6+ deferred GUI: GuiGraphics no longer draws immediately — elements are
 * collected as {@link GuiElementRenderState}s and batched by the GuiRenderer at
 * frame end, with meshes built in each state's pipeline's own vertex format and
 * only the standard UBOs (DynamicTransforms/Projection) bound. Custom uniforms
 * are therefore gone entirely: the SDF parameters ride vertex attributes instead
 * (UV0 = local offset from the rect centre; UV1 = half-size ×16; UV2.x = radius
 * ×16, flat-interpolated), so fills batch like any vanilla element. If the
 * pipeline fails to compile (a pack replaced the GLSL with garbage) every call
 * degrades to a plain square fill, so the GUI never breaks — it just loses its
 * corners.
 */
public final class RoundRect {

    /** 管线定义:着色器位置指 {@code assets/numen_api/shaders/core/rendertype_round_rect.*}。 */
    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "pipeline/round_rect"))
            .withVertexShader(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "core/rendertype_round_rect"))
            .withFragmentShader(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "core/rendertype_round_rect"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withCull(false)
            .build();

    /** SDF 参数的定点倍率(short 属性,1/16 px 精度,尺寸上限 ±2047px)。 */
    private static final int FP = 16;

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
        // 编译失败(资源包换了坏 GLSL)→ 降级方角。precompilePipeline 有缓存,重复调用只是查表。
        if (!RenderSystem.getDevice().precompilePipeline(PIPELINE).isValid()) {
            g.fill(x1, y1, x2, y2, argb);
            return;
        }
        g.guiRenderState.submitGuiElement(new State(
                new Matrix3x2f(g.pose()), x1, y1, x2, y2, radius, argb, g.scissorStack.peek()));
    }

    /** 单个圆角矩形的延迟渲染状态;同管线的连续状态会被 GuiRenderer 合并成一批。 */
    private record State(Matrix3x2f pose, int x0, int y0, int x1, int y1, float radius, int argb,
                         @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
            implements GuiElementRenderState {

        State(Matrix3x2f pose, int x0, int y0, int x1, int y1, float radius, int argb,
              @Nullable ScreenRectangle scissorArea) {
            this(pose, x0, y0, x1, y1, radius, argb, scissorArea,
                    computeBounds(x0, y0, x1, y1, pose, scissorArea));
        }

        @Override
        public void buildVertices(VertexConsumer vc) {
            float hw = (x1 - x0) / 2f;
            float hh = (y1 - y0) / 2f;
            int h16x = Math.round(hw * FP);
            int h16y = Math.round(hh * FP);
            int r16 = Math.round(radius * FP);
            vertex(vc, x0, y0, -hw, -hh, h16x, h16y, r16);
            vertex(vc, x0, y1, -hw, hh, h16x, h16y, r16);
            vertex(vc, x1, y1, hw, hh, h16x, h16y, r16);
            vertex(vc, x1, y0, hw, -hh, h16x, h16y, r16);
        }

        private void vertex(VertexConsumer vc, float x, float y,
                            float lx, float ly, int h16x, int h16y, int r16) {
            vc.addVertexWith2DPose(pose, x, y)
                    .setColor(argb)
                    .setUv(lx, ly)
                    .setUv1(h16x, h16y)
                    .setUv2(r16, 0)
                    .setNormal(0f, 0f, 1f);
        }

        @Override
        public RenderPipeline pipeline() {
            return PIPELINE;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        private static @Nullable ScreenRectangle computeBounds(int x0, int y0, int x1, int y1,
                                                               Matrix3x2f pose,
                                                               @Nullable ScreenRectangle scissor) {
            ScreenRectangle rect = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose);
            return scissor != null ? scissor.intersection(rect) : rect;
        }
    }
}
