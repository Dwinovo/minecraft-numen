package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenPlugins;

import java.nio.file.Path;

/**
 * YSM 联动:让同伴用上 YSM 的模型与动作。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。所以它<b>不是</b>
 * 模组入口——装没装 YSM 由各加载器的 {@code Builtin} 那道闸判断,判断为真才调
 * {@link #install}。YSM 不在的话,这个类一次都不会被加载。
 *
 * <p>登记方式和第三方插件一字不差:全部经 {@code NumenPlugins.register} 那扇门。
 * 编译期也一样——本模块的类路径上只有瘦 api jar 与原版 MC,引擎内部类与加载器的类
 * 都够不着;加载器各不相同的那几件事由 {@link YsmHost} 带进来。
 *
 * <p>对接方式(只走 YSM 的命令,不引用它的类)出自 #37:Dani-732 的兼容补丁先走通了这条路。
 */
public final class NumenYsm {

    private NumenYsm() {}

    /** 由宿主加载器的 {@code Builtin} 在确认 YSM 在场后调用。 */
    public static void install(YsmHost host, Path skillsRoot) {
        Ysm ysm = new Ysm(host.storage());
        YsmCatalog catalog = new YsmCatalog(host.configDir());
        OwnerSync sync = new OwnerSync(ysm);

        NumenPlugins.register(numen -> {
            numen.registerTool(new ListOptionsTool(ysm, catalog));
            numen.registerTool(new SwitchModelTool(ysm, catalog));
            numen.registerTool(new PlayEmoteTool(ysm, catalog));

            if (skillsRoot != null) numen.bundleSkills(skillsRoot);

            // 同伴刚进世界:把主人的授权镜像过去
            numen.on(CompanionEvent.SPAWN, sync::onSpawn);
        });

        host.onServerTick(sync::tick);
    }
}
