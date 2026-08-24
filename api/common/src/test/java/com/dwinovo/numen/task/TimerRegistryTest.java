package com.dwinovo.numen.task;

import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 她自己定的表。
 *
 * <p>三件事这里必须钉死:到期按<b>游戏刻</b>算(世界不跑表就不走)、到期时刻是
 * <b>绝对</b>的(重启不重新计时)、id 跨重启<b>不重号</b>(模型手上那个 id 还认得)。
 */
class TimerRegistryTest {

    private static final UUID SHE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // 1.21.5 起(反)序列化由 SavedDataType 的存储层直接驱动 CODEC,这里也直接对
    // CODEC 往返——测的就是生产在用的那一条路。
    private static TimerRegistry roundTrip(TimerRegistry registry) {
        return TimerRegistry.CODEC.parse(NbtOps.INSTANCE,
                TimerRegistry.CODEC.encodeStart(NbtOps.INSTANCE, registry).result().orElseThrow())
                .result().orElseThrow();
    }

    @Test
    void aTimerComesDueOnlyAfterItsDelayInGameTicks() {
        TimerRegistry registry = new TimerRegistry();
        TimerRegistry.Timer t = registry.set(SHE, 1_000L, 30, "回来收炉子里的铁");

        assertTrue(registry.dueAt(1_599L).isEmpty(), "差一刻就不算到");
        assertEquals(t.id(), registry.dueAt(1_600L).getFirst().id());
        assertEquals(30L, TimerRegistry.remainingSeconds(t, 1_000L));
        assertEquals(0L, TimerRegistry.remainingSeconds(t, 9_999L));
    }

    @Test
    void aRestartDoesNotPutTheTimerBackToTheStart() {
        // 存的是绝对到期刻。存成"还剩多少秒"的话,重启一次就白等一次。
        TimerRegistry registry = new TimerRegistry();
        registry.set(SHE, 1_000L, 60, "看看小麦熟了没");

        TimerRegistry back = roundTrip(registry);

        // 世界跑到 1900 刻(已过 45 秒),剩下的该是 15 秒,不是重新的 60 秒
        assertEquals(15L, TimerRegistry.remainingSeconds(back.list(SHE).getFirst(), 1_900L));
    }

    @Test
    void idsKeepCountingAcrossARestart() {
        TimerRegistry registry = new TimerRegistry();
        String first = registry.set(SHE, 0L, 10, "一").id();

        TimerRegistry back = roundTrip(registry);
        String second = back.set(SHE, 0L, 10, "二").id();

        assertFalse(first.equals(second), "重启后重号,模型手上的旧 id 会撤错表:" + first);
        assertEquals(2, back.list(SHE).size());
    }

    @Test
    void aTimerCanOnlyBeCancelledByTheCompanionThatSetIt() {
        TimerRegistry registry = new TimerRegistry();
        TimerRegistry.Timer t = registry.set(SHE, 0L, 10, "回来看炉子");

        assertFalse(registry.cancel(OTHER, t.id()));
        assertTrue(registry.cancel(SHE, t.id()));
        assertTrue(registry.list(SHE).isEmpty());
    }

    @Test
    void aFullBoardRefusesInsteadOfGrowing() {
        TimerRegistry registry = new TimerRegistry();
        for (int i = 0; i < TimerRegistry.MAX_PER_COMPANION; i++) {
            assertNotNull(registry.set(SHE, 0L, i + 1, "第 " + i + " 个"));
        }
        assertNull(registry.set(SHE, 0L, 60, "多出来的这个"));
        // 上限是每只同伴的,不是全服的
        assertNotNull(registry.set(OTHER, 0L, 60, "别人的表"));
    }

    @Test
    void outOfRangeDelaysAreClampedNotRejected() {
        // 夹住并在回执里说明,比失败强:失败要浪费模型一整轮
        assertEquals(TimerRegistry.MIN_SECONDS, TimerRegistry.clampSeconds(0));
        assertEquals(TimerRegistry.MIN_SECONDS, TimerRegistry.clampSeconds(-5));
        assertEquals(TimerRegistry.MAX_SECONDS, TimerRegistry.clampSeconds(999_999));
        assertEquals(60, TimerRegistry.clampSeconds(60));
    }

    @Test
    void listIsOrderedByWhichFiresFirst() {
        TimerRegistry registry = new TimerRegistry();
        registry.set(SHE, 0L, 600, "晚的");
        registry.set(SHE, 0L, 30, "早的");

        assertEquals("早的", registry.list(SHE).getFirst().reason());
    }
}
