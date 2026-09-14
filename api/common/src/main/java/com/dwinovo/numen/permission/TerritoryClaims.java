package com.dwinovo.numen.permission;

/**
 * 领地 mod 的裁决口:{@code claimed} 信号问它。各 loader 模块装自己的实现
 * (Fabric 接 Common Protection API;NeoForge 由挖掘走原生通道触发 BreakEvent 被拦),
 * 经 {@link Gate} 的构造参数进来。没装时是 {@link #NONE}:没有领地 mod,就没有领地。
 */
@FunctionalInterface
public interface TerritoryClaims {

    /** 没有领地 mod 在场。 */
    TerritoryClaims NONE = (action, facts) -> false;

    /**
     * 领地 mod 说不说不。只在主线程被问({@link Facts#live} 非空);搜索线程按"不知道"
     * 放行,执行到那一格时原生通道自会被拦。
     */
    boolean forbids(Action action, Facts facts);
}
