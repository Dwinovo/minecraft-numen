package com.dwinovo.numen.pathing.search;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * 搜索慢的时候分清是谁慢要读的几个 JVM 量:垃圾回收停下整个 JVM 的次数与毫秒、JIT 编译累计毫秒、当前线程的 CPU 时间。
 * 回收停顿与 JIT 是整个进程的累计值,取两次读数的差才是一段时间里的;主线程每刻的寻路账({@code TickTally})与每次搜索的账
 * ({@link Pending})读的是同一份。
 */
public final class JvmLoad {

    /** 回收器的停顿 bean:并发阶段("Concurrent"、ZGC 的 "Cycles")不停任何线程,不算。 */
    private static final GarbageCollectorMXBean[] PAUSING = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .filter(bean -> !bean.getName().contains("Concurrent") && !bean.getName().contains("Cycles"))
            .toArray(GarbageCollectorMXBean[]::new);
    private static final CompilationMXBean JIT = ManagementFactory.getCompilationMXBean();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final boolean CPU_TIME = THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled();

    private JvmLoad() {}

    public static long gcMillis() {
        long sum = 0;
        for (GarbageCollectorMXBean bean : PAUSING) {
            sum += bean.getCollectionTime();
        }
        return sum;
    }

    public static long gcCount() {
        long sum = 0;
        for (GarbageCollectorMXBean bean : PAUSING) {
            sum += bean.getCollectionCount();
        }
        return sum;
    }

    /** JIT 编译累计毫秒;这个 JVM 不报告为 -1。 */
    public static long jitMillis() {
        return JIT != null && JIT.isCompilationTimeMonitoringSupported() ? JIT.getTotalCompilationTime() : -1;
    }

    /** 当前线程到此刻用掉的 CPU 纳秒;这个 JVM 不支持线程 CPU 计时为 -1。 */
    public static long threadCpuNanos() {
        return CPU_TIME ? THREADS.getCurrentThreadCpuTime() : -1;
    }
}
