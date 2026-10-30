package com.dwinovo.numen.api.plugin;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.scope.Handle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * "登记变了"的那一个信号:花名册装上或撤回一个插件的登记之后发一次,有缓存或有副本的地方据此失效、重建、重发。读登记的地方
 * (系统提示、帮助、脚本目录)每次都从当前的表现算,不缓存,不听这个信号;听的是那些把登记拷到别处的——比如已经发给玩家客户端的
 * {@code /numen} 指令树。
 *
 * <p>一个监听者出错不连累其余。线程:发信号的线程(花名册的操作在哪个线程就是哪个)。
 */
public final class Registrations {

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private Registrations() {}

    /** 听信号;返回不再听的句柄。 */
    public static Handle onChanged(Runnable listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    /** 登记变了。<b>Numen API 内部调用</b>(花名册)。 */
    public static void changed() {
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                Constants.LOG.error("[numen] 登记变了之后有一项跟进失败,其余照常", e);
            }
        }
    }
}
