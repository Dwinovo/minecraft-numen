package com.dwinovo.numen.platform;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 服务器停了 —— 属于那个世界的进程内状态在这里一起作废。
 *
 * <h2>为什么需要它</h2>
 * 单人模式里「退出存档」不结束进程:内置服务器关掉,静态表原封不动地活到下一个
 * 存档。上一局的缓存/大脑/账本因此会在新世界里被当成有效的用下去。
 *
 * <p>2026-08-05 就撞上了最坏的那种:同伴的大脑表活了下来,里面那个还在 RUNNING 的
 * 挖矿任务绑着<b>上一局那具身体</b>。新世界第一个 tick 把它跑起来,它去读旧
 * {@code ServerLevel} 的方块 —— 而那个 level 记的主线程是已经结束的那条 Server thread,
 * 于是 {@code ServerChunkCache.getChunk} 走异步分支,把活派进一个再也没人抽取的队列
 * 然后 {@code join()}。新 Server thread 永久 park,世界再也加载不出来。
 *
 * <h2>为什么是注册制,不是一张清单</h2>
 * 一张写在 loader 里的「关服时丢掉 A、丢掉 B」清单,跟状态本身分居两地:加了 C 就会忘,
 * 而忘掉的那一处正是上面那种死锁的来源。
 *
 * <p>所以反过来:<b>谁持有世界作用域的状态,谁在自己的静态块里报到</b>。清理动作写在
 * 状态旁边,加一处新状态 = 加一行,loader 一个字都不用改,也就没有"忘了登记"这回事。
 * 类没被加载过就不会报到 —— 那正确,它也就没有状态要清。
 *
 * <p>两个 loader 各在服务器停止事件上调一次 {@link #fireStopped()};单人退存档、
 * 专用服关服、切存档,走的都是它。
 */
public final class ServerLifecycle {

    private static final List<Runnable> ON_STOPPED = new CopyOnWriteArrayList<>();

    private ServerLifecycle() {}

    /**
     * 报到:{@code drop} 会在每次服务器停止时被调用,把这份世界作用域的状态清空。
     *
     * <p>写在持有者自己的静态块里。要幂等 —— 连着两次关服(切存档)会调两次。
     */
    public static void onStopped(Runnable drop) {
        ON_STOPPED.add(drop);
    }

    /** 由各 loader 在服务器停止事件上调用。一个清理失败不连累其余。 */
    public static void fireStopped() {
        for (Runnable drop : ON_STOPPED) {
            try {
                drop.run();
            } catch (RuntimeException e) {
                com.dwinovo.numen.Constants.LOG.warn("[numen] 关服清理有一项失败,其余继续", e);
            }
        }
    }
}
