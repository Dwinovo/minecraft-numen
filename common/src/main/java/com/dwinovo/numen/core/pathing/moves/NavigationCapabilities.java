package com.dwinovo.numen.core.pathing.moves;

import java.util.List;
import java.util.Objects;

/**
 * 一次导航允许使用的世界修改能力。能力随 {@link CalculationContext}
 * 传递，不修改全局设置，也不保存到世界数据。
 */
public record NavigationCapabilities(
        boolean allowBreak,
        boolean allowPlace,
        boolean allowWaterBucketLanding) {

    /** 现有导航的兼容预设：保留全局设置原有行为。 */
    public static final NavigationCapabilities DEFAULT =
            new NavigationCapabilities(true, true, true);

    /** 自动跟随的硬限制预设：不允许任何主动世界修改。 */
    public static final NavigationCapabilities SAFE_FOLLOW =
            new NavigationCapabilities(false, false, false);

    public boolean permitsBreak(boolean configured) {
        return allowBreak && configured;
    }

    public boolean permitsPlace(boolean configured) {
        return allowPlace && configured;
    }

    public boolean permitsWaterBucketLanding(boolean configured) {
        return allowWaterBucketLanding && configured;
    }

    /**
     * 导航禁止破坏时，连全局 allowBreakAnyway 例外也必须清空。
     * 默认能力仍返回配置的不可变快照。
     */
    public <T> List<T> permittedBreakExceptions(List<T> configured) {
        Objects.requireNonNull(configured, "configured");
        return allowBreak ? List.copyOf(configured) : List.of();
    }
}
