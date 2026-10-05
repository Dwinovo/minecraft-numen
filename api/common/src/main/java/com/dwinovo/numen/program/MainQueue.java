package com.dwinovo.numen.program;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * 程序的服务端调用排队等服务端主线程的地方。程序在它们自己的线程上跑,调一个服务端函数就往自己的车道({@link Lane})里放一个任务;
 * 主线程每刻开头取一次({@link #drain}),在预算里按车道轮流执行。<b>主线程只执行任务,从不等任何程序</b>:没轮到的留到下一刻,
 * 程序停在那儿等,不占主线程。
 *
 * <p>预算在任务之间查:全部车道一共用了 {@code allNanos} 就停这一刻;一条车道用了 {@code eachNanos} 就轮到下一条。起点每刻往后轮一条,
 * 排在后面的程序不会总是吃不到。一个任务开始了就跑完。
 */
public final class MainQueue {

    /** 一段程序的车道:它的任务按放进去的先后执行。 */
    public static final class Lane {

        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        private volatile boolean closed;

        private Lane() {}

        /** 放一个任务,任何线程。 */
        public void post(Runnable task) {
            tasks.add(task);
        }

        /** 这段程序不会再放任务了:车道里的执行完就撤掉。 */
        public void close() {
            closed = true;
        }
    }

    private final List<Lane> lanes = new CopyOnWriteArrayList<>();
    private final LongSupplier nanos;
    private int turn;

    public MainQueue() {
        this(System::nanoTime);
    }

    /** @param nanos 单调的纳秒钟;预算按它量 */
    MainQueue(LongSupplier nanos) {
        this.nanos = nanos;
    }

    /** 给一段程序开一条车道。 */
    public Lane lane() {
        Lane lane = new Lane();
        lanes.add(lane);
        return lane;
    }

    /**
     * 主线程每刻调一次:在预算里按车道轮流执行排着的任务。
     *
     * @param allNanos  这一刻所有车道一共最多用多久
     * @param eachNanos 一条车道这一刻最多用多久
     */
    public void drain(long allNanos, long eachNanos) {
        List<Lane> snapshot = List.copyOf(lanes);
        if (snapshot.isEmpty()) {
            return;
        }
        int first = turn++ % snapshot.size();
        long spentAll = 0;
        for (int i = 0; i < snapshot.size(); i++) {
            Lane lane = snapshot.get((first + i) % snapshot.size());
            long spent = 0;
            while (spentAll < allNanos && spent < eachNanos) {
                Runnable task = lane.tasks.poll();
                if (task == null) {
                    break;
                }
                long began = nanos.getAsLong();
                task.run();
                long took = nanos.getAsLong() - began;
                spent += took;
                spentAll += took;
            }
            retire(lane);
        }
    }

    /**
     * 不看预算,把排着的任务(包括执行当中又放进来的)都执行完。没有服务器刻的地方(单测)用。
     *
     * @return 执行了几个任务
     */
    public int drainAll() {
        int ran = 0;
        boolean any = true;
        while (any) {
            any = false;
            for (Lane lane : List.copyOf(lanes)) {
                Runnable task;
                while ((task = lane.tasks.poll()) != null) {
                    task.run();
                    ran++;
                    any = true;
                }
                retire(lane);
            }
        }
        return ran;
    }

    /** 关服:排着的任务作废。 */
    public void clear() {
        lanes.clear();
    }

    private void retire(Lane lane) {
        if (lane.closed && lane.tasks.isEmpty()) {
            lanes.remove(lane);
        }
    }
}
