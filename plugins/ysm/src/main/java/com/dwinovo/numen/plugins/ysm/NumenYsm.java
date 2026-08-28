package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;

/**
 * YSM 联动:让同伴用上 YSM 的模型与动作。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。所以它<b>不是</b>
 * {@code @Mod} 入口——装没装 YSM 由 {@code Builtin} 那道闸判断,判断为真才调
 * {@link #install}。YSM 不在的话,这个类<b>一次都不会被加载</b>。
 *
 * <p>登记方式和第三方插件一字不差:全部经 {@code NumenPlugins.register} 那扇门。
 * 编译期也一样——本模块的类路径上只有瘦 api jar,引擎内部类够不着。
 */
public final class NumenYsm {

    private NumenYsm() {}

    /** 由 {@code Builtin} 在确认 YSM 在场后调用。 */
    public static void install(Path skillsRoot) {
        NumenPlugins.register(numen -> {
            numen.registerTool(new ListOptionsTool());
            numen.registerTool(new SwitchModelTool());
            numen.registerTool(new PlayEmoteTool());

            if (skillsRoot != null) numen.bundleSkills(skillsRoot);

            // 同伴刚进世界:把主人的授权镜像过去
            numen.on(CompanionEvent.SPAWN, OwnerSync::onSpawn);
        });

        NeoForge.EVENT_BUS.addListener(
                (ServerTickEvent.Post e) -> OwnerSync.tick(e.getServer()));
    }
}
