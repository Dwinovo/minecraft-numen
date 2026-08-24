package com.dwinovo.numen.core.pathing.util;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.pathing.calc.PathPlannerPool;
import com.dwinovo.numen.core.pathing.settings.NavSettings;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量寻路性能探针,用于定位服务端卡顿是否来自寻路的主线程开销。
 *
 * <p>仅在 {@link NavSettings#profile} 打开时记账;关闭时 {@link #begin()} 返回 0,所有记录点近乎零开销。
 * 寻路执行链({@code PlayerNav.tick} → {@code PathingCore.tick} → {@code PathExecutor.onTick})跑在
 * 服务端主线程且单线程推进,因此这里的静态状态无需加锁。
 *
 * <p>按 ~5 秒窗口把各阶段的 {次数 / 总耗时 / 峰值} 以及并发导航数聚合成一行 INFO 日志。总耗时相对窗口
 * 长度的占比,直接反映寻路吃掉了多少主线程时间;峰值抓单 tick 卡顿。开关:运行期把
 * {@code NavSettings.get().profile = true}(默认 false)。
 */
public final class NavProfiler {

    private NavProfiler() {}

    private static final long WINDOW_MS = 5_000L;

    private static long windowStartMs = 0L;
    /** 本窗口内 PlayerNav.tick() 的总调用次数(≈ 并发导航数 × 经过的 tick 数)。 */
    private static long navTicks = 0L;
    /** 阶段名 -> [count, totalNanos, maxNanos]。仅主线程写读,无需加锁。 */
    private static final Map<String, long[]> phases = new LinkedHashMap<>();

    // 后台 A* 搜索在池线程上记账,故用原子量;主线程 flush 时 getAndSet 归零。
    private static final AtomicLong searchCount = new AtomicLong();
    private static final AtomicLong searchTotalNanos = new AtomicLong();
    private static final AtomicLong searchMaxNanos = new AtomicLong();
    /** 上一窗口末的累计完成数,用于算窗口吞吐。 */
    private static long lastCompletedTasks = 0L;
    /** 上一窗口末的 JVM 累计 GC 次数 / 停顿毫秒,用于算本窗口 GC。 */
    private static long lastGcCount = 0L;
    private static long lastGcTimeMs = 0L;

    /** 计时起点;探针关闭时返回 0,{@link #end} 会据此直接跳过。 */
    public static long begin() {
        return NavSettings.get().profile ? System.nanoTime() : 0L;
    }

    /** 记一个阶段的耗时。{@code start == 0}(探针关闭)时无操作。 */
    public static void end(String phase, long start) {
        if (start == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - start;
        long[] a = phases.get(phase);
        if (a == null) {
            a = new long[3];
            phases.put(phase, a);
        }
        a[0]++;
        a[1] += elapsed;
        if (elapsed > a[2]) {
            a[2] = elapsed;
        }
    }

    /** 记一次后台 A* 搜索的耗时(在池线程上调用,线程安全)。{@code start == 0} 时无操作。 */
    public static void recordSearch(long start) {
        if (start == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - start;
        searchCount.incrementAndGet();
        searchTotalNanos.addAndGet(elapsed);
        long prev;
        do {
            prev = searchMaxNanos.get();
        } while (elapsed > prev && !searchMaxNanos.compareAndSet(prev, elapsed));
    }

    // 服务端 tick 间隔脉冲:活跃任务每 tick 打一次点,相邻脉冲的间隔≈一个完整服务端 tick 的
    // 墙钟长度。它能抓到落在本探针各相位【之外】的主线程尖峰(光照、方块更新、未计时的任务
    // 工作等)——是区分"服务端在卡"还是"客户端在卡"的决定性信号:间隔全程 ~50ms 而画面仍
    // 顿,元凶必在客户端。
    private static long lastPulseMs = 0L;
    private static long pulseGapMax = 0L;
    private static long pulseOver60 = 0L;
    private static long pulseOver100 = 0L;

    /** 活跃任务每 tick 调用一次;探针关闭时无操作。间隔 >2s 视为任务空档,不计入。 */
    public static void serverTickPulse() {
        if (!NavSettings.get().profile) {
            return;
        }
        serverThread = Thread.currentThread();
        ensureSampler();
        long now = System.currentTimeMillis();
        if (lastPulseMs != 0L) {
            long gap = now - lastPulseMs;
            if (gap < 2000L) {
                if (gap > pulseGapMax) {
                    pulseGapMax = gap;
                }
                if (gap > 60L) {
                    pulseOver60++;
                }
                if (gap > 100L) {
                    dumpStallStacks(lastPulseMs, now, gap);
                    pulseOver100++;
                }
            }
        }
        lastPulseMs = now;
    }

    // ==================== 卡顿现场抓栈 ====================
    // 后台采样线程 25ms 一次抓服务端主线程的调用栈存进环形缓冲;tick 间隔一超 100ms,
    // 就把卡顿区间内采到的栈打成 [nav-stall] 日志——直接指认主线程当时堵在哪个方法里,
    // 覆盖所有计时相位之外的盲区(光照、区块 I/O、别的 mod……)。profile 关闭时采样线程自杀。

    private static volatile Thread serverThread;
    private static volatile boolean samplerRunning;
    private static final int STALL_SAMPLES = 64;   // 25ms × 64 ≈ 1.6s 的现场窗口
    private static final long[] sampleTimes = new long[STALL_SAMPLES];
    private static final String[] sampleStacks = new String[STALL_SAMPLES];
    private static final AtomicLong sampleWrites = new AtomicLong();

    private static synchronized void ensureSampler() {
        if (samplerRunning) {
            return;
        }
        samplerRunning = true;
        Thread sampler = new Thread(() -> {
            while (NavSettings.get().profile) {
                Thread target = serverThread;
                if (target != null && target.isAlive()) {
                    StackTraceElement[] st = target.getStackTrace();
                    if (st.length > 0) {
                        int slot = (int) (sampleWrites.get() % STALL_SAMPLES);
                        sampleStacks[slot] = condense(st);
                        sampleTimes[slot] = System.currentTimeMillis();
                        sampleWrites.incrementAndGet();
                    }
                }
                try {
                    Thread.sleep(25L);
                } catch (InterruptedException e) {
                    break;
                }
            }
            samplerRunning = false;
        }, "numen-stall-sampler");
        sampler.setDaemon(true);
        sampler.setPriority(Thread.MIN_PRIORITY);
        sampler.start();
    }

    /** 栈顶压缩成一行:最多 10 帧,类名只留最后两段。 */
    private static String condense(StackTraceElement[] st) {
        StringBuilder sb = new StringBuilder(240);
        int frames = Math.min(st.length, 10);
        for (int i = 0; i < frames; i++) {
            StackTraceElement e = st[i];
            String cls = e.getClassName();
            int cut = cls.lastIndexOf('.');
            cut = cut <= 0 ? 0 : cls.lastIndexOf('.', cut - 1) + 1;
            if (i > 0) {
                sb.append(" < ");
            }
            sb.append(cls, cut, cls.length()).append('.').append(e.getMethodName())
                    .append(':').append(e.getLineNumber());
        }
        return sb.toString();
    }

    /** 把 (from, to] 区间内采到的、去重后的栈样本打成日志(最多 3 条,防刷屏)。 */
    private static void dumpStallStacks(long from, long to, long gap) {
        java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<>();
        for (int i = 0; i < STALL_SAMPLES; i++) {
            long t = sampleTimes[i];
            String s = sampleStacks[i];
            if (s != null && t > from && t <= to) {
                distinct.add(s);
            }
        }
        int logged = 0;
        for (String s : distinct) {
            Constants.LOG.info("[nav-stall] gap={}ms stack: {}", gap, s);
            if (++logged >= 3) {
                break;
            }
        }
        if (distinct.isEmpty()) {
            Constants.LOG.info("[nav-stall] gap={}ms (no samples captured in window)", gap);
        }
    }

    /** 每次 PlayerNav.tick() 调用一次:统计并发导航数,并按窗口触发汇总日志。 */
    public static void tickFrame() {
        if (!NavSettings.get().profile) {
            return;
        }
        navTicks++;
        long now = System.currentTimeMillis();
        if (windowStartMs == 0L) {
            windowStartMs = now;
            // Snapshot the running-total baselines so the FIRST window reports window deltas, not the
            // JVM's lifetime GC / completed-task totals (which would make the first line read absurd).
            lastCompletedTasks = PathPlannerPool.completedTasks();
            long[] gc = gcTotals();
            lastGcCount = gc[0];
            lastGcTimeMs = gc[1];
        } else if (now - windowStartMs >= WINDOW_MS) {
            flush(now);
        }
    }

    private static void flush(long now) {
        long windowMs = Math.max(1L, now - windowStartMs);
        StringBuilder sb = new StringBuilder(256);
        sb.append("[nav-profile] 窗口 ").append(windowMs).append("ms  navTicks=").append(navTicks)
                .append(" (~").append(navTicks * 1000L / windowMs).append("/s)");
        for (Map.Entry<String, long[]> e : phases.entrySet()) {
            long[] a = e.getValue();
            double totalMs = a[1] / 1_000_000.0;
            double avgUs = a[0] == 0 ? 0.0 : (a[1] / 1_000.0) / a[0];
            double maxMs = a[2] / 1_000_000.0;
            sb.append(String.format("  | %s: n=%d 总=%.1fms 均=%.1fus 峰=%.2fms",
                    e.getKey(), a[0], totalMs, avgUs, maxMs));
        }

        // 后台搜索(池线程)——总耗时是 CPU 时间,可远超窗口墙钟(并行跑在多核上)
        long sc = searchCount.getAndSet(0L);
        long st = searchTotalNanos.getAndSet(0L);
        long sm = searchMaxNanos.getAndSet(0L);
        if (sc > 0) {
            sb.append(String.format("  | search(后台): n=%d 总=%.1fms 峰=%.1fms",
                    sc, st / 1_000_000.0, sm / 1_000_000.0));
        }

        // 线程池快照——有界池下峰值线程封顶在 POOL_SIZE;峰值持续贴上限=搜索在排队(负载高),
        // 已不再是旧无界池那种"线程爆炸饿死主线程"。
        long completed = PathPlannerPool.completedTasks();
        sb.append(String.format("  | pool: 线程=%d 活跃=%d 峰值=%d 完成+%d",
                PathPlannerPool.liveThreads(), PathPlannerPool.activeThreads(),
                PathPlannerPool.peakThreads(), completed - lastCompletedTasks));
        lastCompletedTasks = completed;

        // GC:JVM 级、全线程 stop-the-world。窗口内 GC 停顿占墙钟越高,越是"全实体一起顿"的直接来源
        // ——且它落在寻路计时之外,所以上面 goal.compile/core.tick 看着干净也不能排除它。
        long[] gc = gcTotals();
        long gcCount = gc[0];
        long gcTimeMs = gc[1];
        sb.append(String.format("  | GC: 次数+%d 停顿+%dms(占窗口 %.1f%%)",
                gcCount - lastGcCount, gcTimeMs - lastGcTimeMs,
                (gcTimeMs - lastGcTimeMs) * 100.0 / windowMs));
        lastGcCount = gcCount;
        lastGcTimeMs = gcTimeMs;

        // 服务端 tick 间隔:gapMax 接近 50ms = 服务端顺;频繁 >60/>100 = 主线程有探针相位外的尖峰
        if (pulseGapMax > 0L) {
            sb.append(String.format("  | tick: gapMax=%dms over60=%d over100=%d",
                    pulseGapMax, pulseOver60, pulseOver100));
            pulseGapMax = 0L;
            pulseOver60 = 0L;
            pulseOver100 = 0L;
        }

        Constants.LOG.info(sb.toString());

        windowStartMs = now;
        navTicks = 0L;
        phases.clear();
    }

    /** JVM 累计 GC {次数, 停顿毫秒}(汇总所有收集器)。 */
    private static long[] gcTotals() {
        long count = 0L;
        long timeMs = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gc.getCollectionCount();
            long t = gc.getCollectionTime();
            if (c > 0) {
                count += c;
            }
            if (t > 0) {
                timeMs += t;
            }
        }
        return new long[]{count, timeMs};
    }

    /** 探针开/关切换时调用:丢弃当前窗口,下次 {@link #tickFrame} 从干净基线重启,避免跨开关的偏斜窗口。 */
    public static void reset() {
        windowStartMs = 0L;
        navTicks = 0L;
        phases.clear();
        searchCount.set(0L);
        searchTotalNanos.set(0L);
        searchMaxNanos.set(0L);
        lastPulseMs = 0L;
        pulseGapMax = 0L;
        pulseOver60 = 0L;
        pulseOver100 = 0L;
    }
}
