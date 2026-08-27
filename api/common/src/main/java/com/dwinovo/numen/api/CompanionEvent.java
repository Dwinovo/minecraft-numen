package com.dwinovo.numen.api;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.UUID;

/**
 * 同伴身上会发生的事,插件用 {@code numen.on(事件, 处理器)} 订阅。
 *
 * <h2>为什么是常量而不是一个方法一个事件</h2>
 * 一个事件一个静态方法({@code onSpawn} / {@code onDeath} / …)的话,<b>每加一个事件
 * 就得改一次 API 的形状</b>——加方法、发版、消费者升级。而这里加事件只是多一个常量,
 * 订阅那一头 {@code on(…)} 一个字都不用动。
 *
 * <p>泛型参数是处理器收到的东西。事件名只用于日志与排错,订阅时用的是常量本身,
 * 拼错了编译期就不过。
 */
public final class CompanionEvent<T> {

    /** 身体刚进世界(召唤或从休眠唤醒),已经在玩家列表里、可以对它下命令了。 */
    public static final CompanionEvent<NumenPlayer> SPAWN = new CompanionEvent<>("companion_spawn");

    /** 身体正在离开世界(休眠 / 注销 / 死亡后消失)。此刻它还在,之后就没了。 */
    public static final CompanionEvent<NumenPlayer> REMOVE = new CompanionEvent<>("companion_remove");

    /** 身体刚死,尸体还没被清走。 */
    public static final CompanionEvent<NumenPlayer> DEATH = new CompanionEvent<>("companion_death");

    /** 主人打断了这个同伴的思考——手上没做完的活该放弃了。给的是 UUID,因为身体未必还在。 */
    public static final CompanionEvent<UUID> ABORT = new CompanionEvent<>("companion_abort");

    private final String name;

    private CompanionEvent(String name) {
        this.name = name;
    }

    public String eventName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
