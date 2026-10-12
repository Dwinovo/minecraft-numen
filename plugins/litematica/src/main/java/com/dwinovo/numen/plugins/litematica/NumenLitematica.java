package com.dwinovo.numen.plugins.litematica;

import com.dwinovo.numen.api.NumenPlugins;

import java.nio.file.Path;

/**
 * Litematica 联动:把主人用 Litematica 画的图纸搬进 Numen 的图纸库,好让同伴照着盖。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。Litematica 在不在由 {@code Builtin}
 * 那道闸判断,判断为真才调 {@link #install}——它不在的话,这个类<b>一次都不会被加载</b>。
 *
 * <p>Litematica 是客户端模组,图纸存在主人的电脑上;Numen 的图纸库在服务端。所以这组函数在主人
 * 客户端上跑(第一个参数是 {@code ClientCall}),把文件从主人的图纸目录拷进服务器的工作目录。
 * 只有主人本机开着世界时两端才是同一台机器,那之外(连别人的服务器)同步不了,如实报错。
 */
public final class NumenLitematica {

    /** 这个联动在她的 API 里的名字空间:{@code litematica.*}。 */
    static final String NAMESPACE = "litematica";

    private NumenLitematica() {}

    /** 由 {@code Builtin} 在确认 Litematica 在场后调用。 */
    public static void install(Path skillsRoot) {
        NumenPlugins.register(NAMESPACE, numen -> {
            LitematicaApi.install(numen);
            if (skillsRoot != null) numen.bundleSkills(skillsRoot);
        });
    }
}
