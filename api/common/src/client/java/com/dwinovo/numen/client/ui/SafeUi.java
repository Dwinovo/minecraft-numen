package com.dwinovo.numen.client.ui;

import com.dwinovo.numen.Constants;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 用户面的崩溃护栏:渲染/点击/tick 里的任何异常都不许带走游戏——吃掉、
 * 限频记日志(同一区域 5s 一条,渲染是逐帧的,不限频日志会爆炸)、
 * 调用方按返回值画降级提示。玩家的最坏体验应当是"这块界面坏了一行字",
 * 而不是回到桌面。
 */
public final class SafeUi {

    private static final long LOG_INTERVAL_MS = 5000;
    private static final Map<String, Long> LAST_LOG = new HashMap<>();

    private SafeUi() {}

    /** 跑一段用户面代码;异常吃掉并记日志。@return true = 正常走完 */
    public static boolean run(String zone, Runnable body) {
        try {
            body.run();
            return true;
        } catch (Throwable t) {
            note(zone, t);
            return false;
        }
    }

    /** 点击类:异常按"未消费该点击"降级。 */
    public static boolean click(String zone, BooleanSupplier body) {
        try {
            return body.getAsBoolean();
        } catch (Throwable t) {
            note(zone, t);
            return false;
        }
    }

    private static void note(String zone, Throwable t) {
        long now = System.currentTimeMillis();
        Long last = LAST_LOG.get(zone);
        if (last == null || now - last > LOG_INTERVAL_MS) {
            LAST_LOG.put(zone, now);
            Constants.LOG.error("[numen-ui] {} crashed — swallowed to keep the game alive", zone, t);
        }
    }
}
