package com.dwinovo.numen.client.debug;

import com.dwinovo.numen.network.payload.PathDebugPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.List;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 寻路调试世界渲染:把 {@link PathDebugState} 里的快照画成世界空间的
 * 线与方框——当前路径红、下一段品红、在飞最优蓝、待挖红框、待放绿框、
 * 挤身品红框、目标绿框、x/z 目标画通天竖线。逐帧矢量绘制,零粒子。
 * 线条会被地形遮挡(深度测试开启,原版 lines 管线)。
 */
public final class PathDebugRenderer {

    private static final float[] RED = {1.0f, 0.15f, 0.15f};
    private static final float[] MAGENTA = {1.0f, 0.25f, 1.0f};
    private static final float[] BLUE = {0.25f, 0.45f, 1.0f};
    private static final float[] GREEN = {0.15f, 1.0f, 0.15f};

    private PathDebugRenderer() {}

    /** 世界渲染钩子入口(半透明方块阶段之后;poseStack 为世界空间)。 */
    public static void render(PoseStack poseStack, Camera camera) {
        List<PathDebugPayload> snapshots = PathDebugState.live();
        if (snapshots.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Vec3 cam = camera.getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = poseStack.last();
        int minY = mc.level.getMinY();
        int maxY = mc.level.getMaxY();
        for (PathDebugPayload p : snapshots) {
            drawPolyline(vc, pose, p.currentPath(), RED);
            drawPolyline(vc, pose, p.nextPath(), MAGENTA);
            drawPolyline(vc, pose, p.bestPath(), BLUE);
            for (long packed : p.toBreak()) {
                drawBox(poseStack, vc, packed, RED);
            }
            for (long packed : p.toPlace()) {
                drawBox(poseStack, vc, packed, GREEN);
            }
            for (long packed : p.toWalkInto()) {
                drawBox(poseStack, vc, packed, MAGENTA);
            }
            for (long packed : p.goalBoxes()) {
                drawBox(poseStack, vc, packed, GREEN);
            }
            for (long packed : p.goalColumns()) {
                BlockPos pos = BlockPos.of(packed);
                seg(vc, pose,
                        pos.getX() + 0.5, minY, pos.getZ() + 0.5,
                        pos.getX() + 0.5, maxY, pos.getZ() + 0.5, GREEN);
            }
        }
        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }

    /** 折线:相邻格心连线段。 */
    private static void drawPolyline(VertexConsumer vc, PoseStack.Pose pose,
                                     List<Long> packedPositions, float[] color) {
        for (int i = 0; i + 1 < packedPositions.size(); i++) {
            BlockPos a = BlockPos.of(packedPositions.get(i));
            BlockPos b = BlockPos.of(packedPositions.get(i + 1));
            seg(vc, pose,
                    a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5,
                    b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, color);
        }
    }

    private static void drawBox(PoseStack poseStack, VertexConsumer vc, long packed, float[] color) {
        BlockPos pos = BlockPos.of(packed);
        net.minecraft.client.renderer.ShapeRenderer.renderLineBox(poseStack, vc,
                pos.getX() + 0.02, pos.getY() + 0.02, pos.getZ() + 0.02,
                pos.getX() + 0.98, pos.getY() + 0.98, pos.getZ() + 0.98,
                color[0], color[1], color[2], 0.9f);
    }

    /** 一条线段(法线取线段方向,lines 渲染管线要求)。 */
    private static void seg(VertexConsumer vc, PoseStack.Pose pose,
                            double x1, double y1, double z1,
                            double x2, double y2, double z2, float[] color) {
        float dx = (float) (x2 - x1);
        float dy = (float) (y2 - y1);
        float dz = (float) (z2 - z1);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-5f) {
            return;
        }
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;
        vc.addVertex(pose, (float) x1, (float) y1, (float) z1)
                .setColor(color[0], color[1], color[2], 0.9f)
                .setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, (float) x2, (float) y2, (float) z2)
                .setColor(color[0], color[1], color[2], 0.9f)
                .setNormal(pose, nx, ny, nz);
    }
}
