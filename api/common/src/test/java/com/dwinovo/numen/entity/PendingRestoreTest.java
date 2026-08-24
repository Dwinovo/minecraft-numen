package com.dwinovo.numen.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主人登录后，同伴的恢复排在队列里、下一个服务端 tick 才做。
 *
 * <p>为什么不当场做:登录事件跑在原版 {@code PlayerList.placeNewPlayer()} <b>内部</b>。在那儿
 * 恢复同伴，等于把同伴的问题接到主人的入场流程上——一只同伴的存档有毛病、或者别的模组在
 * 同伴的入场事件里抛了，冒泡上去打断的是<b>主人的登录</b>，客户端只看到"无效的玩家数据"，
 * 他会以为自己的存档毁了。而同伴入场会触发它自己的登录事件，装在这个世界里的任何模组都
 * 收得到，这一类我们永远堵不完，只能不让它落在主人头上。
 */
class PendingRestoreTest {

    private static UUID owner() {
        return UUID.randomUUID();
    }

    /** 排空一次，记下都轮到了谁。 */
    private static List<UUID> drain() {
        List<UUID> seen = new ArrayList<>();
        Companions.drainPending(seen::add);
        return seen;
    }

    @Test
    void aScheduledOwnerIsRestoredOnTheNextDrain() {
        UUID a = owner();
        Companions.scheduleRestoreFor(a);
        assertEquals(List.of(a), drain());
    }

    @Test
    void anOwnerIsRestoredOnceNotEveryTick() {
        // 先摘牌再执行。反过来的话,失败的那个会每 tick 重试到天荒地老
        Companions.scheduleRestoreFor(owner());
        assertEquals(1, drain().size());
        assertTrue(drain().isEmpty(), "第二个 tick 不该再恢复一遍");
    }

    @Test
    void schedulingTheSameOwnerTwiceStillRestoresOnce() {
        UUID a = owner();
        Companions.scheduleRestoreFor(a);
        Companions.scheduleRestoreFor(a);
        assertEquals(List.of(a), drain());
    }

    @Test
    void oneOwnerBlowingUpDoesNotStopTheOthers() {
        // 主人之间毫无关系,没有理由一起倒
        UUID a = owner();
        UUID bad = owner();
        UUID c = owner();
        Companions.scheduleRestoreFor(a);
        Companions.scheduleRestoreFor(bad);
        Companions.scheduleRestoreFor(c);

        List<UUID> reached = new ArrayList<>();
        Companions.drainPending(id -> {
            reached.add(id);
            if (id.equals(bad)) {
                throw new IllegalStateException("这只同伴的存档坏了");
            }
        });

        assertEquals(3, reached.size(), "三个都得轮到,炸掉的那个不能带走后面的");
    }

    @Test
    void anOwnerThatBlewUpIsNotRetriedForever() {
        // 恢复失败是坏事,每 tick 重复失败并刷屏是更坏的事
        UUID bad = owner();
        Companions.scheduleRestoreFor(bad);
        Companions.drainPending(id -> {
            throw new IllegalStateException("坏了");
        });
        assertTrue(drain().isEmpty(), "失败过的不该赖在队列里");
    }

    @Test
    void anEmptyQueueDoesNothingAtAll() {
        // 每 tick 都调一次,绝大多数 tick 队列是空的
        drain();
        assertTrue(drain().isEmpty());
    }
}
