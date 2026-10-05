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
 * 头顶气泡的渲染入口:生物实体渲染里,与名牌同一条管线——这是光影
 * /着色器正确处理的路径(世界渲染阶段的裸几何在 Iris 下会被吃掉)。
 * 没有气泡的实体(包括所有真人玩家)一次 map 查询即返回,零开销。
 *
 * <p>1.21.2+ 的渲染状态化改造:{@code PlayerRenderer} 不再自己实现
 * {@code render},绘制统一落在 {@code LivingEntityRenderer.render(状态,…)}
 * 上,入参也从实体换成了 {@code PlayerRenderState}。所以挂载点落在
 * 这里,靠状态类型筛出玩家、再用状态里的实体网络 id 取回本体。
 *
 * <h2>为什么挂在 HEAD 而不是 TAIL</h2>
 * 替换同伴身体的插件靠取消渲染事件来接管渲染,而 NeoForge 把那个事件编译成
 * {@code if (post(pre)) return;}——取消等于本方法整个提前返回,挂在尾部的东西
 * 一个都不跑。气泡是同伴的核心表达,不能因为换了个模型就没了。挂在头部则先于
 * 那次判断执行,谁接管身体都不影响。
 *
 * <p>位置对渲染结果没有影响:头尾两处的 {@code PoseStack} 是同一个状态(本方法
 * push/pop 对称),气泡自己 push/translate;先后顺序也不决定画面前后——
 * {@code MultiBufferSource} 按 RenderType 分批,批次在实体渲染阶段结束时统一刷出。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MixinLivingEntityRenderer {

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
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
