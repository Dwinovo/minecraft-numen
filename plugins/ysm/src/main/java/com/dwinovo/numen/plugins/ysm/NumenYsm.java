package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.plugin.PluginRoster;

import java.nio.file.Path;

/**
 * YSM 联动:让同伴用上 YSM 的模型与动作。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。所以它<b>不是</b>
 * 模组入口——装没装 YSM 由各加载器的 {@code Builtin} 那道闸判断,判断为真才调
 * {@link #install}。YSM 不在的话,这个类一次都不会被加载。
 *
 * <p>登记方式和第三方插件一字不差:全部经 {@code NumenPlugins.register} 那扇门。
 * 编译期也一样——本模块的类路径上只有瘦 api jar 与原版 MC,Numen API 内部类与加载器的类
 * 都够不着;加载器各不相同的那几件事由 {@link YsmHost} 带进来。
 *
 * <p>对接方式(只走 YSM 的命令,不引用它的类)出自 #37:Dani-732 的兼容补丁先走通了这条路。
 */
public final class NumenYsm {

    private NumenYsm() {}

    /** 由宿主加载器的 {@code Builtin} 在确认 YSM 在场后调用。 */
    public static void install(YsmHost host, Path content) {
        Ysm ysm = new Ysm(host.storage());
        OwnerSync sync = new OwnerSync(ysm);
        Emotes emotes = new Emotes(ysm);

        // 主人授权的镜像与表情每刻挂在宿主的钩子上(门外),撤不回:卸下要重启
        NumenPlugins.register(YsmApi.NAMESPACE, PluginRoster.Unloading.NEEDS_RESTART, numen -> {
            YsmApi.install(numen, ysm, emotes);
            // ysm.model.switch 派下来的换装由谁来跑
            numen.taskRunner(SwitchRecord.class, (player, record) -> new SwitchTask(ysm, emotes, record));

            if (content != null) numen.bundleContent(content);

            // 同伴刚进世界:把主人的授权镜像过去
            numen.on(CompanionEvent.SPAWN, sync::onSpawn);
            numen.on(CompanionEvent.SPAWN, emotes::onSpawn);
        });

        host.onServerTick(sync::tick);
        host.onServerTick(emotes::tick);
    }
}
