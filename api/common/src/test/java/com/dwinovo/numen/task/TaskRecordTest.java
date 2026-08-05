package com.dwinovo.numen.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 常驻与有界的分界线——整套统一就落在这一个数上。
 *
 * <p>期限回答的是"这件活该多久干完"。常驻任务<b>没有干完</b>,所以给它
 * {@link TaskRecord#NO_DEADLINE}:一个永远不会到的游戏刻。判据因此是本地的、
 * 不需要额外声明——{@code TaskDispatch} 据此决定回执怎么写,客户端据此告诉模型
 * "这件活不会有 task_finished"。
 */
class TaskRecordTest {

    private static final class Fake extends TaskRecord {
        Fake(long deadline) {
            super("fake", "call-1", deadline);
        }

        @Override public String describe() {
            return "假的";
        }
    }

    @Test
    void noDeadlineNeverArrives() {
        // 世界一天 24000 刻;这个数意味着几万亿年 —— 它不会到
        long aVeryLongGameLater = 24_000L * 365 * 1_000_000;
        assertTrue(TaskRecord.NO_DEADLINE > aVeryLongGameLater);
    }

    @Test
    void freezingAStandingTaskCannotOverflow() {
        // 被抢占时期限会 +1(TaskSlot.freeze)。用 MAX_VALUE 的话,常驻任务被反射
        // 抢占几次就会溢出成负数 —— 那一刻 gameTime >= deadline 立刻成立,
        // 她手上的活会毫无征兆地"超时"。留一半余量就是为了这个。
        Fake standing = new Fake(TaskRecord.NO_DEADLINE);
        for (int i = 0; i < 1000; i++) {
            standing.extendDeadlineTo(standing.getDeadlineGameTime() + 1);
        }
        assertTrue(standing.getDeadlineGameTime() > 0, "期限溢出成负数了");
        assertTrue(standing.getDeadlineGameTime() >= TaskRecord.NO_DEADLINE);
    }

    @Test
    void standingIsTellableFromTheDeadlineAlone() {
        // 不需要额外的 standing 字段:期限本身就说明了它有没有终点
        assertTrue(new Fake(TaskRecord.NO_DEADLINE).getDeadlineGameTime() >= TaskRecord.NO_DEADLINE);
        assertFalse(new Fake(12_000L).getDeadlineGameTime() >= TaskRecord.NO_DEADLINE);
    }

    @Test
    void deadlinesOnlyEverMoveLater() {
        // 冻结用的是"只往后不往前"的语义:一次抢占不该把别人已经争取到的宽限抹掉
        Fake bounded = new Fake(1000L);
        bounded.extendDeadlineTo(2000L);
        bounded.extendDeadlineTo(1500L);
        org.junit.jupiter.api.Assertions.assertEquals(2000L, bounded.getDeadlineGameTime());
    }
}
