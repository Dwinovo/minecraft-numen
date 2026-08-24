package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.hud.SpeechBubbleRenderer;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 头顶气泡的渲染入口:玩家实体渲染尾部,与名牌同一条管线——这是光影
 * /着色器正确处理的路径(世界渲染阶段的裸几何在 Iris 下会被吃掉)。
 * 没有气泡的实体(包括所有真人玩家)一次 map 查询即返回,零开销。
 */
@Mixin(PlayerRenderer.class)
public abstract class MixinPlayerRenderer {

    @Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("TAIL"))
    private void numen$speechBubble(AbstractClientPlayer entity, float entityYaw, float partialTicks,
                                    PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                    CallbackInfo ci) {
        SpeechBubbleRenderer.render(entity, poseStack, buffers);
    }
}
