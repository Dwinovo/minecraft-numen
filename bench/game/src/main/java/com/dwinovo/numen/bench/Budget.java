package com.dwinovo.numen.bench;

/**
 * 一次运行最多花多少:调几次模型、多少分钟。分钟同时是游戏时间与墙钟的上限——评测服务器每秒 20 刻,等模型回话时
 * 游戏照走,两者本来差不多;哪个先到按哪个收场。
 */
public record Budget(int turns, int minutes) {

    public static final Budget DEFAULT = new Budget(30, 10);

    public Budget {
        if (turns < 1 || minutes < 1) {
            throw new IllegalArgumentException("budget must allow at least one turn and one minute");
        }
    }

    public int ticks() {
        return minutes * 60 * 20;
    }

    public long wallMs() {
        return minutes * 60_000L;
    }
}
