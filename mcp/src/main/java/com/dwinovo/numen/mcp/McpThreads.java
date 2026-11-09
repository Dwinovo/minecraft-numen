package com.dwinovo.numen.mcp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本模块起的线程都从这里来:线程是哪一种只在这一处决定,别处只说"起一条叫这个名字的线程"或"每个任务一条线程"。
 *
 * <p>用虚拟线程(Java 21 起有):服务端每个 HTTP 请求、每次工具调用,客户端每个在途请求、每条读流,都各占一条线程并阻塞着等,
 * 虚拟线程让这种写法不必数线程。虚拟线程都是守护线程,所以这里起的线程不拦 JVM 退出;从它们上新起的平台线程随之也是守护线程——
 * {@code McpEndpoint} 靠这一点让 HttpServer 的分派线程是守护的。
 */
public final class McpThreads {

    private McpThreads() {
    }

    /** 起一条叫 {@code name} 的线程跑 {@code task}。 */
    public static Thread start(String name, Runnable task) {
        return Thread.ofVirtual().name(name).start(task);
    }

    /** 每个任务一条线程的执行器,线程名是 {@code prefix} 接从 0 起的序号。 */
    public static ExecutorService perTask(String prefix) {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(prefix, 0).factory());
    }
}
