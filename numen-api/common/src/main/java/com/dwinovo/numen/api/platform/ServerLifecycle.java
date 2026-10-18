package com.dwinovo.numen.api.platform;

import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

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
 * <p>插件的世界作用域状态(缓存、在飞的搜索)同样在这里报到,和 Numen API 自己的走同一扇门。
 *
 * <p>两个 loader 各在服务器停止事件上调一次 {@link #fireStopped()};单人退存档、
 * 专用服关服、切存档,走的都是它。起来的那一刻同理:{@link #fireStarted} 让需要世界的东西(同伴的大脑、外接大脑宿主)
 * 在服务器起好后各自开工。
 */
public final class ServerLifecycle {

    private static final List<Runnable> ON_STOPPED = new CopyOnWriteArrayList<>();
    private static final List<Consumer<MinecraftServer>> ON_STARTED = new CopyOnWriteArrayList<>();
    private static final List<Consumer<MinecraftServer>> ON_RELOADED = new CopyOnWriteArrayList<>();
    /** 起好了、还没停的那台服务器;{@link #fireReloaded} 对它调。 */
    private static volatile MinecraftServer running;

    private ServerLifecycle() {}

    /**
     * 报到:{@code drop} 会在每次服务器停止时被调用,把这份世界作用域的状态清空。
     *
     * <p>写在持有者自己的静态块里。要幂等 —— 连着两次关服(切存档)会调两次。
     */
    public static void onStopped(Runnable drop) {
        ON_STOPPED.add(drop);
    }

    /** 报到:服务器起好之后(世界已加载)调 {@code start}。 */
    public static void onStarted(Consumer<MinecraftServer> start) {
        ON_STARTED.add(start);
    }

    /** 报到:管理员用 {@code /reload} 重载数据之后调 {@code reload}——磁盘上手改的库、钥匙串和设置在这里重新读。 */
    public static void onReloaded(Consumer<MinecraftServer> reload) {
        ON_RELOADED.add(reload);
    }

    /** 由各 loader 在服务器起好的事件上调用。一个失败不连累其余。 */
    public static void fireStarted(MinecraftServer server) {
        running = server;
        for (Consumer<MinecraftServer> start : ON_STARTED) {
            try {
                start.accept(server);
            } catch (RuntimeException e) {
                com.dwinovo.numen.api.Constants.LOG.error("[numen] 服务器起来时有一项失败,其余继续", e);
            }
        }
    }

    /**
     * 由各 loader 在数据重载完成时调用(服务器起好之前的那次装载不算:此时没有起好的服务器,什么也不做)。
     * 一个失败不连累其余。
     */
    public static void fireReloaded() {
        MinecraftServer server = running;
        if (server == null) {
            return;
        }
        for (Consumer<MinecraftServer> reload : ON_RELOADED) {
            try {
                reload.accept(server);
            } catch (RuntimeException e) {
                com.dwinovo.numen.api.Constants.LOG.error("[numen] 重载时有一项失败,其余继续", e);
            }
        }
    }

    /** 由各 loader 在服务器停止事件上调用。一个清理失败不连累其余。 */
    public static void fireStopped() {
        running = null;
        for (Runnable drop : ON_STOPPED) {
            try {
                drop.run();
            } catch (RuntimeException e) {
                com.dwinovo.numen.api.Constants.LOG.warn("[numen] 关服清理有一项失败,其余继续", e);
            }
        }
    }
}
