package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/**
 * 车万女仆联动:让同伴穿上车万女仆的模型,也让她像人一样养自己的女仆。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。所以它<b>不是</b>
 * {@code @Mod} 入口——装没装车万女仆由 {@code Builtin} 那道闸判断,判断为真才调
 * {@link #install}。它不在的话,这个类<b>一次都不会被加载</b>,而这一点是必须的:
 * 本联动直接编译依赖车万女仆的类({@code BedrockModel} 等),类加载了就会去找那些类。
 *
 * <p>穿什么模型是她身体的属性,在服务端({@link Outfit}),同步给每个看到她的客户端去渲染;查装了哪些模型在主人客户端——
 * 按名字搜靠客户端的语言表。养女仆在服务端——女仆是世界里的实体,驯服、切工作模式、开她的界面都是对她做的事,
 * P 点与女仆数记在她身上。
 */
public final class NumenTlm {

    /** 这个联动在她的 API 里的名字空间:{@code tlm.maid.*}、{@code tlm.skin.*}、{@code tlm.altar.*}。 */
    static final String NAMESPACE = "tlm";

    private NumenTlm() {}

    /** 由 {@code Builtin} 在确认车万女仆在场后调用。 */
    public static void install(IEventBus modBus, Path skillsRoot) {
        Outfit.register(modBus);
        NumenPlugins.register(NAMESPACE, numen -> {
            // 几组 API 两侧都登记(帮助要它们的说明);查模型名册的在主人客户端跑,穿脱模型、管女仆与祭坛的在服务端跑
            SkinApi.install(numen);

            // 女仆身上的事件两侧都登记(服务端的发出口靠它挡,主人客户端的队列靠它投递),所以不放进 onClient
            MaidEvents.bind(numen);
            Maids.listen();
            // 每轮都告诉她身上的 P 点、车万女仆给她记的女仆数
            numen.contributeBodyState(Maids::bodyState);
            // 她穿着哪套是身体的事实,每轮都在,不看她在不在主人的视野里
            numen.contributeBodyState(MaidLook::describe);

            numen.onClient(() -> {
                // 受伤和死亡自动出声:情绪最强、频率天然低,不会变成噪音。
                // 其余时刻由她自己用 make_sound 决定——理由见 MaidVoice。
                numen.on(com.dwinovo.numen.api.CompanionEvent.HURT,
                        h -> MaidVoice.onHurt(h.companion().getUUID(), h.source()));
                numen.on(com.dwinovo.numen.api.CompanionEvent.DEATH,
                        body -> MaidVoice.onDeath(body.getUUID()));

                modBus.addListener(MaidBody::onAddLayers);
                NeoForge.EVENT_BUS.addListener(MaidBody::render);
                NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> MaidBody.tick());
                NeoForge.EVENT_BUS.addListener(
                        (ClientPlayerNetworkEvent.LoggingOut e) -> MaidBody.forget());
            });

            if (skillsRoot != null) numen.bundleSkills(skillsRoot);
        });
    }
}
