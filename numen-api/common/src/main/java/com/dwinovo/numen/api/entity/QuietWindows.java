package com.dwinovo.numen.api.entity;

import java.util.HashMap;
import java.util.Map;

/**
 * 按对象去抖:同一个对象在一个窗口(游戏刻)里至多放行一次。她挨打({@link CompanionHurtWatch},整场连击算一个对象)和有东西
 * 盯上她({@link TargetingWatch},每只怪各算一个)用同一把窗口,一场混战不会把她吵醒个没完。
 *
 * <p>纯 JVM。过期的窗口随下一次问询丢掉,表只会涨到"窗口里出现过几个对象"。
 */
final class QuietWindows<K> {

    private final long ticks;
    private final Map<K, Long> until = new HashMap<>();

    QuietWindows(long ticks) {
        this.ticks = ticks;
    }

    /** 这个对象这一刻放不放行:窗口里已放行过就不放;放行了就从此刻起关 {@code ticks} 刻。 */
    boolean admit(K key, long now) {
        until.values().removeIf(end -> end <= now);
        if (until.containsKey(key)) {
            return false;
        }
        until.put(key, now + ticks);
        return true;
    }
}
