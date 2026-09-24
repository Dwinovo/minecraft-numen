package com.dwinovo.numen.client.consent;

import com.dwinovo.numen.network.payload.ConsentRequestPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 世界里给挂着的征询涉及的方块与实体描轮廓——主人抬头就知道她问的是哪几块、哪一只。
 * 只照 {@link ConsentCards} 画,请求撤回轮廓就没了。与寻路调试覆盖层同一条世界渲染管线。
 */
public final class ConsentOutlines {

    private static final float R = 1.0f;
    private static final float G = 0.72f;
    private static final float B = 0.1f;

    private ConsentOutlines() {}

    /** 世界渲染钩子入口(半透明方块阶段之后;poseStack 为世界空间)。 */
    public static void render(PoseStack poseStack, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ConsentCards.all().isEmpty()) {
            return;
        }
        Vec3 cam = camera.getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        for (ConsentCards.Card card : ConsentCards.all()) {
            ConsentRequestPayload request = card.request();
            for (long packed : request.blocks()) {
                BlockPos pos = BlockPos.of(packed);
                LevelRenderer.renderLineBox(poseStack, vc,
                        pos.getX() - 0.01, pos.getY() - 0.01, pos.getZ() - 0.01,
                        pos.getX() + 1.01, pos.getY() + 1.01, pos.getZ() + 1.01, R, G, B, 1.0f);
            }
            for (int id : request.entities()) {
                Entity entity = mc.level.getEntity(id);
                if (entity != null) {
                    AABB box = entity.getBoundingBox().inflate(0.05);
                    LevelRenderer.renderLineBox(poseStack, vc, box, R, G, B, 1.0f);
                }
            }
        }
        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
