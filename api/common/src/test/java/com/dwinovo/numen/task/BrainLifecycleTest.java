package com.dwinovo.numen.task;

import com.dwinovo.numen.entity.CompanionSpeech;
import com.dwinovo.numen.platform.ServerLifecycle;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 大脑属于世界,不属于进程。
 *
 * <h2>这一条是拿死锁换来的</h2>
 * 单人「退出存档」不结束进程,静态的大脑表原样活到下一个存档。2026-08-05 那次:
 * 表里那个还在 RUNNING 的挖矿任务绑着<b>上一局那具身体</b>,新世界第一个 tick 把它
 * 跑起来,它拿旧 {@code ServerLevel} 去读方块——而那个 level 记的主线程早就结束了,
 * {@code ServerChunkCache.getChunk} 于是走异步分支,把活派进一个再没人抽取的队列
 * 然后 {@code join()}。新 Server thread 永久 park,世界再也加载不出来。
 *
 * <p>「退游戏再进」一切正常、「退存档再进」必死,就是这条不变式的对照实验:
 * 前者销毁了进程,后者没有。
 */
class BrainLifecycleTest {

    @Test
    void brainsAreDroppedWhenTheServerStops() {
        UUID her = UUID.randomUUID();

        TaskSlot before = CompanionTickDispatcher.syncSlotFor(her);
        assertSame(before, CompanionTickDispatcher.syncSlotFor(her),
                "同一个世界里,同一只同伴每次拿到的应该是同一个大脑");

        ServerLifecycle.fireStopped();   // = 退出存档 / 关服

        assertNotSame(before, CompanionTickDispatcher.syncSlotFor(her),
                "关服后还拿到旧大脑——它装着绑在旧世界身体上的任务");
    }

    @Test
    void theDispatcherRegistersItselfWithoutAnyLoaderHelp() {
        // 上面那条测的是行为,这条测的是<b>为什么不会漏</b>:清理动作写在状态旁边的
        // 静态块里,类被加载就已经报到了,loader 一个字都不用写,也就没有"忘了登记"。
        UUID her = UUID.randomUUID();
        TaskSlot fresh = CompanionTickDispatcher.currentSlotFor(her);

        ServerLifecycle.fireStopped();

        assertNotSame(fresh, CompanionTickDispatcher.currentSlotFor(her));
    }

    @Test
    void speakingFlagsAreWorldScopedToo() {
        // 同族的另一份:服务端记的"谁在说话"。一个没翻回来的 true 会让下一个存档里
        // 的她一直盯着主人看。
        UUID her = UUID.randomUUID();
        CompanionSpeech.setSpeaking(her, true);

        ServerLifecycle.fireStopped();

        assertFalse(CompanionSpeech.isSpeaking(her));
    }
}
