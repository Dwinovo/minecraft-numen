package com.dwinovo.numen.plugins.tlm;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 把同伴画成女仆。
 *
 * <h2>为什么直接借车万女仆的渲染器,而不是自己画模型</h2>
 * {@code EntityMaidRenderer} 是 {@code MobRenderer<Mob, ...>}——它的 {@code render}
 * 和 {@code getTextureLocation} 收的就是裸 {@code Mob},内部没有一处转成
 * {@code EntityMaid}。所以喂一只 {@link MaidPuppet} 进去,贴图查找、缩放、朝向、
 * 手持物与头部装备层全部按女仆的原样跑。
 *
 * <p>反过来自己画的话,就得把 {@code LivingEntityRenderer} 那套变换、以及车万女仆
 * 的贴图/缩放规则各抄一份——同一件事两处实现,它一升级我这边就错位。
 *
 * <h2>和 YSM 同装时谁赢:车万女仆,且是确定的</h2>
 * YSM 靠 {@code LivingRendererMixin} 挂在 {@code LivingEntityRenderer.render} 上,
 * 而 {@code PlayerRenderer.render} 是<b>先</b>发 {@code RenderPlayerEvent.Pre}(字节码
 * 偏移 24)、后调 {@code super.render}(偏移 49)。所以这里一取消,YSM 的 mixin 根本
 * 轮不到。脱下女仆模型就把身体还回去,YSM 照旧生效——不需要两个插件互相认识。
 *
 * <h2>代价:原生渲染被取消,气泡跟着没了</h2>
 * NeoForge 把 {@code RenderPlayerEvent.Pre} 编译成
 * {@code if (post(pre)) return;}——取消等于整个 {@code PlayerRenderer.render} 提前
 * 返回,引擎挂在它尾部的说话气泡也一起不画。见 {@link #render} 里的处理。
 */
public final class MaidBody {

    private static final Logger LOG = LoggerFactory.getLogger("numen-tlm");

    private static EntityMaidRenderer renderer;
    private static final Map<UUID, MaidPuppet> PUPPETS = new HashMap<>();

    private MaidBody() {}

    /**
     * 建渲染器。只有这一个时机拿得到 {@code EntityRendererProvider.Context},
     * 而车万女仆的渲染器构造就要它(手持物渲染器、模型集都从里面取)。
     */
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        EntityRendererProvider.Context ctx = event.getContext();
        try {
            renderer = new EntityMaidRenderer(ctx);
        } catch (Throwable t) {
            LOG.warn("车万女仆的渲染器建不起来,同伴照常渲染成玩家: {}", t.toString());
        }
    }

    /** 换存档/退出:傀儡连同它们引用的世界一起作废,否则会拖着上一个 Level 不放。 */
    public static void forget() {
        PUPPETS.clear();
    }

    /** 走路动画得按 tick 推,不能按帧推——理由见 {@link MaidPuppet#advanceWalk()}。 */
    public static void tick() {
        for (MaidPuppet p : PUPPETS.values()) p.advanceWalk();
    }

    /**
     * 玩家渲染的入口。不是同伴、或这只同伴没穿女仆模型,就原样放行——
     * 一次 map 查询,对所有真人玩家零开销。
     */
    public static void render(RenderPlayerEvent.Pre event) {
        if (renderer == null || Wardrobe.empty()) return;

        AbstractClientPlayer player = (AbstractClientPlayer) event.getEntity();
        String modelId = Wardrobe.worn(player.getUUID());
        if (modelId == null) return;

        // 模型包被玩家删了/改名了:当作没穿,别把同伴画成一团空气。
        // 判据见 Tlm.exists——问元信息表,不能问 Bedrock 模型表。
        if (!Tlm.exists(modelId)) return;

        MaidPuppet puppet = PUPPETS.computeIfAbsent(player.getUUID(), id -> new MaidPuppet(player.level()));
        puppet.mirror(player, modelId);

        event.setCanceled(true);
        renderer.render(puppet, player.getYRot(), event.getPartialTick(),
                event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight());
    }
}
