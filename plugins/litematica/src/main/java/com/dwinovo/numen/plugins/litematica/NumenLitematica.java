package com.dwinovo.numen.plugins.litematica;

import com.dwinovo.numen.api.NumenPlugins;

import java.nio.file.Path;

/**
 * Litematica 联动:她能按主人加载的投影逐层建,也能把自己的设计做成投影给主人预览。
 *
 * <p>建造本身在 Numen({@code numen.build.place|diff} 收一串格);这个联动只是多一个来源(投影的格)和多一种预览(设计变投影),
 * 交回的格照 Numen 收的写法,原样能传给它们。Numen 的代码里没有 Litematica。
 *
 * <p>Litematica 是<b>目标模组只在客户端</b>的联动:登记在服务端与客户端无条件做({@code Builtin} 的闸门不问目标模组在不在),经
 * {@code NumenPlugins.registerForClientMod};"有没有"由主人的客户端自己问平台、报给服务端,服务端据此决定模型的 API 清单里有没有它、
 * 调用放不放行(见 {@code ClientMods})。所以这个类和 {@link LitematicaApi} 的签名里都没有 Litematica 的类型;直接引用 Litematica 的
 * {@link Projections}、{@link Preview} 只在函数真被执行时才被加载——函数全是客户端函数,只会在装着 Litematica 的客户端上执行。
 */
public final class NumenLitematica {

    /** 目标模组的 id:Litematica(Fabric)与 Forgematica(NeoForge,它登记的模组 id 也叫 litematica)。 */
    private static final String TARGET_MOD = "litematica";

    private NumenLitematica() {}

    /** 由 {@code Builtin} 的闸门({@code Gate#openClientMod})放行后调用,不问 Litematica 在不在。 */
    public static void install(Path content) {
        NumenPlugins.registerForClientMod(LitematicaApi.NAMESPACE, TARGET_MOD, numen -> {
            LitematicaApi.install(numen);
            if (content != null) {
                numen.bundleContent(content);
            }
        });
    }
}
