package com.dwinovo.numen.api.client.consent;

import com.dwinovo.numen.api.network.payload.ConsentRequestPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 世界里给挂着的征询涉及的方块与实体描轮廓——主人抬头就知道她问的是哪几块、哪一只。
 * 只照 {@link ConsentCards} 画,请求撤回轮廓就没了。与寻路调试覆盖层同一条世界渲染管线。
 */
public final class ConsentOutlines {

    private static final int COLOR = ARGB.colorFromFloat(1.0f, 1.0f, 0.72f, 0.1f);
    /** 方块轮廓比整格略大一圈。 */
    private static final VoxelShape BLOCK = Shapes.box(-0.01, -0.01, -0.01, 1.01, 1.01, 1.01);

    private ConsentOutlines() {}

    /** 世界渲染钩子入口(半透明方块阶段之后;poseStack 为世界空间)。 */
    public static void render(PoseStack poseStack, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ConsentCards.all().isEmpty()) {
            return;
        }
        Vec3 cam = camera.position();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderTypes.lines());
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        float width = mc.getWindow().getAppropriateLineWidth();
        for (ConsentCards.Card card : ConsentCards.all()) {
            ConsentRequestPayload request = card.request();
            for (long packed : request.blocks()) {
                BlockPos pos = BlockPos.of(packed);
                ShapeRenderer.renderShape(poseStack, vc, BLOCK, pos.getX(), pos.getY(), pos.getZ(), COLOR, width);
            }
            for (int id : request.entities()) {
                Entity entity = mc.level.getEntity(id);
                if (entity != null) {
                    AABB box = entity.getBoundingBox().inflate(0.05);
                    ShapeRenderer.renderShape(poseStack, vc, Shapes.create(box), 0, 0, 0, COLOR, width);
                }
            }
        }
        poseStack.popPose();
        buffers.endBatch(RenderTypes.lines());
    }
}
