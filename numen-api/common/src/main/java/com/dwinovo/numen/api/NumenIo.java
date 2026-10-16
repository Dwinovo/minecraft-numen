package com.dwinovo.numen.api;

import com.dwinovo.numen.store.Io;

/**
 * Numen API 里各存储落盘用的 {@link Io}:现在都在调用线程上读写(数据还在客户端、小而少),
 * 写失败记一条带标签的警告——存盘出问题不该让游戏停摆。换到后台线程只改这一处。
 */
public final class NumenIo {

    private NumenIo() {}

    /** @param tag 日志标签,如 {@code numen-voice} */
    public static Io inline(String tag) {
        return Io.direct(e -> Constants.LOG.warn("[{}] {}", tag, e.getMessage()));
    }
}
