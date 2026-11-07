package com.dwinovo.numen.api;

import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.Lanes;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Numen API 里各存储落盘用的 {@link Io}。两种:
 * <ul>
 *   <li>{@link #lanes}:服务端的东西(她的日志、收件箱、札记,主人的库)——后台的串行车道,一个归属一条,
 *       停机时排空;写失败记一条带标签的警告,存盘出问题不该让游戏停摆;</li>
 *   <li>{@link #inline}:客户端自己的偏好与库,在调用线程上读写(数据小而少)。</li>
 * </ul>
 */
public final class NumenIo {

    /** 停机时等车道排空至多多久;到点还没排空就记一条警告,有写没落下去。 */
    private static final long DRAIN_SECONDS = 10;

    private static final Lanes LANES = new Lanes(Executors.newVirtualThreadPerTaskExecutor(),
            e -> Constants.LOG.warn("[numen-io] {}", e.getMessage()));

    static {
        ServerLifecycle.onStopped(NumenIo::drain);
    }

    private NumenIo() {}

    /** 服务端各存储共用的车道表。 */
    public static Lanes lanes() {
        return LANES;
    }

    /** @param tag 日志标签,如 {@code numen-voice} */
    public static Io inline(String tag) {
        return Io.direct(e -> Constants.LOG.warn("[{}] {}", tag, e.getMessage()));
    }

    private static void drain() {
        try {
            if (!LANES.drain(DRAIN_SECONDS, TimeUnit.SECONDS)) {
                Constants.LOG.warn("[numen-io] {} 秒内没有排空写盘的车道,最后几笔可能没落下去", DRAIN_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Constants.LOG.warn("[numen-io] 停机时等写盘被打断,最后几笔可能没落下去");
        }
    }
}
