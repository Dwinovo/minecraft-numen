package com.dwinovo.numen.plugins;

import com.dwinovo.numen.ModJar;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * 本加载器内嵌了哪些联动、各自要谁——以及加载器替它们做的那几件事。
 *
 * <p>清单在这里,不做扫描:内嵌联动是<b>闭合集合</b>,数量由我们自己定;扫描是给开放
 * 集合用的。列在一处,"现在内嵌了哪些、各自要谁"一眼答得完。闸门本身三个加载器共用,
 * 见 {@link Gate}。
 *
 * <p>只有 Litematica:本分支(1.21.4)的其它联动在 Fabric 上没有目标模组(JEI 这一版只有 NeoForge 构建)。
 *
 * <h2>联动的类型只许出现在嵌套类里</h2>
 * 本类自己的方法(含 lambda 编译出来的合成方法)一个都不能提联动的类型:校验器为了核对
 * 参数类型会把它们提前加载,而开发运行(datagen、runClient)里联动不在类路径上——
 * compileOnly——提前加载就是 {@code ClassNotFoundException},整个模组入口跟着炸。
 * 各联动的接线放进各自的嵌套类,闸门开了才碰到它。
 */
public final class Builtin {

    private Builtin() {}

    public static void registerAll() {
        Gate gate = new Gate(FabricLoader.getInstance()::isModLoaded, ModJar::find);
        gate.openClientMod("litematica", "litematica", content -> () -> LitematicaOnFabric.install(content));
    }

    /** Litematica 联动不需要加载器替它做什么事:函数都在主人的客户端上跑,经 Numen API 的同一条反向请求。 */
    private static final class LitematicaOnFabric {
        static void install(Path content) {
            com.dwinovo.numen.plugins.litematica.NumenLitematica.install(content);
        }
    }
}
