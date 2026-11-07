package com.dwinovo.numen.plugins.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/**
 * 向 JEI 报到,拿它的运行时。JEI 自己找这个类(NeoForge 扫 {@code @JeiPlugin},Fabric 读 {@code fabric.mod.json} 的
 * {@code jei_mod_plugin}),所以 JEI 不在时它一次也不会被加载。运行时在 JEI 载完配方后给、退出世界时收回,来源的函数只在有运行时的时候能答。
 */
@JeiPlugin
public final class NumenJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath("numen", "jei");

    /** 此刻的运行时;JEI 还没载完或已退出是 null。 */
    private static volatile IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    /** 此刻的 JEI 运行时;没有时是 null。 */
    static IJeiRuntime runtime() {
        return runtime;
    }
}
