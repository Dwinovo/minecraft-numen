package com.dwinovo.numen.entity;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.api.CompanionEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 同伴生命周期事件的总线——引擎在这边 {@link #fire},插件那边经
 * {@code numen.on(事件, 处理器)} 订阅。
 *
 * <h2>为什么按事件常量分桶,而不是一个事件一个列表</h2>
 * 一个事件一个静态列表 + 一个 {@code onXxx} + 一个 {@code fireXxx} 的话,加一个事件
 * 要改三处;而这里加事件只是在 {@link CompanionEvent} 里多一个常量,总线一个字不动。
 *
 * <p>订阅是类型安全的:桶的键带着 {@code CompanionEvent<T>} 的 T,
 * {@code on(SPAWN, (String s) -> …)} 编译期就过不去。
 */
public final class CompanionEvents {

    private static final Map<CompanionEvent<?>, List<Consumer<?>>> BUCKETS = new ConcurrentHashMap<>();

    private CompanionEvents() {}

    /** 插件侧入口在 {@code NumenApi#on};这里是它落到的地方。 */
    public static <T> void subscribe(CompanionEvent<T> event, Consumer<T> handler) {
        if (event == null || handler == null) return;
        BUCKETS.computeIfAbsent(event, e -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * 引擎侧触发。某个订阅者抛异常只记日志、不打断其余订阅者——一个插件写坏了不该
     * 让别的插件跟着收不到事件。
     */
    @SuppressWarnings("unchecked")
    public static <T> void fire(CompanionEvent<T> event, T payload) {
        List<Consumer<?>> handlers = BUCKETS.get(event);
        if (handlers == null) return;
        for (Consumer<?> h : handlers) {
            try {
                ((Consumer<T>) h).accept(payload);
            } catch (RuntimeException e) {
                Constants.LOG.error("[numen] 插件处理 {} 事件时出错", event, e);
            }
        }
    }
}
