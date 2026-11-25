package com.dwinovo.numen.api.agent.trace;

/**
 * 这个进程记不记轨迹。默认不记:轨迹只增不删、每刻要整理一遍身体,服主的服务器上没有人读它。大脑与身体各自在自己的进程里读这一个开关,
 * 各写各的那一本({@link TraceRecorder}),不经它互相够着。
 *
 * <p>线程:任何线程。
 */
public final class TraceRecorders {

    private static volatile boolean recording;

    private TraceRecorders() {}

    /** 这个进程从现在起给之后建的大脑与身体记轨迹。评测与读轨迹的用例在建同伴之前调。 */
    public static void record() {
        recording = true;
    }

    /** 这个进程记不记轨迹。 */
    public static boolean recording() {
        return recording;
    }
}
