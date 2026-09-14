package com.dwinovo.numen.permission;

/**
 * 领地 mod 的裁决口:{@code claimed} 信号问它。加载器模块在模组初始化时经
 * {@link Permission#useTerritoryClaims} 装上自己的实现(Fabric 在 Common Protection API 在场时接它),
 * {@link Permission#gateFor} 把它放进裁决快照。没装时是 {@link #NONE}:没有领地 mod,就没有领地——
 * 挖掘与放置照样走原生通道,领地 mod 在那里取消事件时由落点如实报成被拦(NeoForge 就是这样)。
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
