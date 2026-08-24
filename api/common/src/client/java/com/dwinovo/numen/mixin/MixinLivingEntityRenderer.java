package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.hud.SpeechBubbleRenderer;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 头顶气泡的渲染入口:生物实体渲染尾部,与名牌同一条管线——这是光影
 * /着色器正确处理的路径(世界渲染阶段的裸几何在着色器下会被吃掉)。
 * 没有气泡的实体(包括所有真人玩家)一次 map 查询即返回,零开销。
 *
 * <p>1.21.2+ 的渲染状态化改造:{@code PlayerRenderer} 不再自己实现
 * {@code render},绘制统一落在 {@code LivingEntityRenderer.render(状态,…)}
 * 上,入参也从实体换成了 {@code PlayerRenderState}。所以挂载点下移到
 * 这里,靠状态类型筛出玩家、再用状态里的实体网络 id 取回本体——位姿
 * 时机与旧代完全一致(TAIL 处 poseStack 已 pop 回实体原点)。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MixinLivingEntityRenderer {

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("TAIL"))
    private void numen$speechBubble(LivingEntityRenderState state, PoseStack poseStack,
                                    MultiBufferSource buffers, int packedLight, CallbackInfo ci) {
        if (!(state instanceof PlayerRenderState player)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity body = mc.level.getEntity(player.id);
        if (body instanceof AbstractClientPlayer p) {
            SpeechBubbleRenderer.render(p, poseStack, buffers);
        }
    }
}
