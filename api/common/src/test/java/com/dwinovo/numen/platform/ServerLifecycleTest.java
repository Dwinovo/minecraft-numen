package com.dwinovo.numen.platform;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关服清理的契约。
 *
 * <p>它挡住的是这种死锁:单人退存档不结束进程,进程内的世界作用域状态活到下一个存档,
 * 里面绑着旧身体的任务能把新的 Server thread 永久 park 住。清理写成"每个 loader 各列
 * 一份要丢哪几样"的话,清单和状态分居两地,迟早漏掉一样。所以这里测的重点只有两条:
 * <b>报到的都会被调</b>,<b>一个炸了不连累其余</b>。
 */
class ServerLifecycleTest {

    @Test
    void everyRegisteredDropRuns() {
        List<String> ran = new ArrayList<>();
        ServerLifecycle.onStopped(() -> ran.add("a"));
        ServerLifecycle.onStopped(() -> ran.add("b"));

        ServerLifecycle.fireStopped();

        assertTrue(ran.contains("a"));
        assertTrue(ran.contains("b"));
    }

    @Test
    void oneFailingDropDoesNotStopTheRest() {
        // 关服路径上一处清理抛异常,不能把后面的清理全截断——那正是登出 NPE 那次的
        // 形状(一个 NPE 吃掉了花名册清空等八项后续)。
        List<String> ran = new ArrayList<>();
        ServerLifecycle.onStopped(() -> {
            throw new IllegalStateException("清理时炸了");
        });
        ServerLifecycle.onStopped(() -> ran.add("after"));

        ServerLifecycle.fireStopped();

        assertTrue(ran.contains("after"), "前一项抛异常后,后面的清理没跑");
    }

    @Test
    void firingTwiceIsSafe() {
        // 连着切两个存档 = 连着两次关服;清理必须幂等
        List<String> ran = new ArrayList<>();
        ServerLifecycle.onStopped(() -> ran.add("x"));

        ServerLifecycle.fireStopped();
        ServerLifecycle.fireStopped();

        assertEquals(2, ran.stream().filter("x"::equals).count());
    }
}
