package com.dwinovo.numen.api.mind;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** 要在座位上等答复的活的线程:每件活一条虚拟线程,不占大脑的线程。 */
public final class Background {

    private static final Executor THREADS = Executors.newVirtualThreadPerTaskExecutor();

    private Background() {}

    public static void run(Runnable work) {
        THREADS.execute(work);
    }
}
