package com.dwinovo.numen.plugins.jei;

/**
 * 加载器各不相同的那一件事:JEI 的流体成分在 NeoForge 上是 {@code FluidStack}、在 Fabric 上是 {@code IJeiFluidIngredient},数量的读法不同
 * (公共 API 里没有读数量的口子)。联动只写原版与 JEI 的公共 API,这一件由各加载器的宿主回答。
 */
public interface JeiHost {

    /**
     * 一份 JEI 流体成分有多少毫桶。
     *
     * @param fluid JEI 的流体成分({@code IPlatformFluidHelper#getFluidIngredientType} 那种类型的值)
     */
    long milliBuckets(Object fluid);
}
