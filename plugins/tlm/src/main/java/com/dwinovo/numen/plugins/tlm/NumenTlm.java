package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/**
 * 车万女仆联动:让同伴穿上车万女仆的模型。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。所以它<b>不是</b>
 * {@code @Mod} 入口——装没装车万女仆由 {@code Builtin} 那道闸判断,判断为真才调
 * {@link #install}。它不在的话,这个类<b>一次都不会被加载</b>,而这一点是必须的:
 * 本联动直接编译依赖车万女仆的类({@code BedrockModel} 等),类加载了就会去找那些类。
 *
 * <p>整件事全在客户端——模型包是主人自己装的,只有这一侧知道装了哪些。
 */
public final class NumenTlm {

    private NumenTlm() {}

    /** 由 {@code Builtin} 在确认车万女仆在场后调用。 */
    public static void install(IEventBus modBus, Path skillsRoot) {
        NumenPlugins.register(numen -> {
            numen.onClient(() -> {
                numen.registerTool(new ListMaidModelsTool());
                numen.registerTool(new WearMaidModelTool());

                Wardrobe.bind(numen.configDir());
                Wardrobe.load();

                // 每轮都告诉她现在穿的是谁,而不是只在换装那一轮
                numen.contributeState(MaidLook::describe);

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
