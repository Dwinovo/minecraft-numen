package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.api.NumenPlugins;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.common.MinecraftForge;

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

                // 这个 MC 版本上车万女仆没有按坐标播语音的口(1.20.1 的它既没有
                // MaidSoundInstanceAtPos 也没有 ICustomSoundBuffer),所以这条分支只做
                // 模型,不做语音。高版本分支上有。

                modBus.addListener(MaidBody::onAddLayers);
                MinecraftForge.EVENT_BUS.addListener(MaidBody::render);
                MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
                    if (e.phase == TickEvent.Phase.END) MaidBody.tick();
                });
                MinecraftForge.EVENT_BUS.addListener(
                        (ClientPlayerNetworkEvent.LoggingOut e) -> MaidBody.forget());
            });

            if (skillsRoot != null) numen.bundleSkills(skillsRoot);
        });
    }
}
