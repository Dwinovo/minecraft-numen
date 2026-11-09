package com.dwinovo.numen.mcp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本模块起的线程都从这里来:线程是哪一种只在这一处决定,别处只说"起一条叫这个名字的线程"或"每个任务一条线程"。
 *
 * <p>这一代分支是 Java 17,没有虚拟线程(Java 21 才有),用守护的平台线程。服务端每个 HTTP 请求、每次工具调用,客户端每个在途请求、
 * 每条读流,都各占一条线程并阻塞着等;长时间占着线程的是工具调用(等宿主),它的并发由 {@code McpLimits} 管着(每张令牌与整个端点
 * 各有上限)。执行器是缓存线程池:来一个任务没有闲着的线程就新起一条,闲下来的线程过一会儿收回。守护线程不拦 JVM 退出;从它们上新起的
 * 平台线程随之也是守护线程——{@code McpEndpoint} 靠这一点让 HttpServer 的分派线程是守护的。
 */
public final class McpThreads {

    private McpThreads() {
    }

    /** 起一条叫 {@code name} 的线程跑 {@code task}。 */
    public static Thread start(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** 每个任务一条线程的执行器,线程名是 {@code prefix} 接从 0 起的序号。 */
    public static ExecutorService perTask(String prefix) {
        AtomicLong next = new AtomicLong();
        return Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, prefix + next.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }
}
