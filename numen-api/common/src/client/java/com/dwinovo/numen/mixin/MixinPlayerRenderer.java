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
 * 头顶气泡的渲染入口:玩家实体渲染里,与名牌同一条管线——这是光影
 * /着色器正确处理的路径(世界渲染阶段的裸几何在 Iris 下会被吃掉)。
 * 没有气泡的实体(包括所有真人玩家)一次 map 查询即返回,零开销。
 *
 * <h2>为什么挂在 HEAD 而不是 TAIL</h2>
 * 替换同伴身体的插件(如车万女仆插件)靠取消 {@code RenderPlayerEvent.Pre} 来接管
 * 渲染,而 NeoForge 把那个事件编译成 {@code if (post(pre)) return;}——取消等于本方法
 * 整个提前返回,挂在尾部的东西一个都不跑。气泡是同伴的核心表达,不能因为换了个
 * 模型就没了。挂在头部则先于那次判断执行,谁接管身体都不影响。
 *
 * <p>位置对渲染结果没有影响:头尾两处的 {@code PoseStack} 是同一个状态(本方法
 * push/pop 对称),气泡自己 push/translate;先后顺序也不决定画面前后——
 * {@code MultiBufferSource} 按 RenderType 分批,批次在实体渲染阶段结束时统一刷出。
 */
@Mixin(PlayerRenderer.class)
public abstract class MixinPlayerRenderer {

    @Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void numen$speechBubble(AbstractClientPlayer entity, float entityYaw, float partialTicks,
                                    PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                    CallbackInfo ci) {
        SpeechBubbleRenderer.render(entity, poseStack, buffers);
    }
}
