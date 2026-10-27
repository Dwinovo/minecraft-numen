package com.dwinovo.numen.plugins.litematica;

import com.dwinovo.numen.api.NumenPlugins;

import java.nio.file.Path;

/**
 * Litematica 联动:她能按主人加载的投影逐层建,也能把自己的设计做成投影给主人预览。
 *
 * <p>建造本身在 Numen({@code numen.build.place|diff|raise} 收一串格);这个联动只是多一个来源(投影的格)和多一种预览(设计变投影),
 * 交回的格照 Numen 收的写法,原样能传给它们。Numen 的代码里没有 Litematica。
 *
 * <p>它不是模组入口:装没装 Litematica 由 Fabric 端 {@code Builtin} 那道闸判断,为真才调 {@link #install}。Litematica 不在的话,这个类
 * 与 {@link Projections}、{@link Preview} 一次都不会被加载——后两个直接引用 Litematica 的类。登记的函数签名里没有 Litematica 的类型,
 * 函数真被执行时才碰到它们;而 Litematica 是纯客户端模组,所以函数都在主人的客户端上执行,见 {@link LitematicaApi}。
 */
public final class NumenLitematica {

    private NumenLitematica() {}

    /** 由 {@code Builtin} 在确认 Litematica 在场后调用。 */
    public static void install(Path content) {
        NumenPlugins.register(LitematicaApi.NAMESPACE, numen -> {
            LitematicaApi.install(numen);
            if (content != null) {
                numen.bundleContent(content);
            }
        });
    }
}
