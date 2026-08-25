package com.dwinovo.numen.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入队列的全部规则。
 *
 * <p>这层决定的是主人对这个模组的第一印象:开口太勤是聒噪,该说的时候不说是死气沉沉,
 * 而两者都会被当成 BUG。规则只有一条——<b>急件、攒够条数、攒够时长,锁着就等</b>——
 * 所以这套测试的重点是<b>它真的没有第四条</b>:不看类型、不看谁发的、不看她在干嘛。
 */
class EventQueueTest {

    private static final long T0 = 1_000_000L;

    private static EventQueue fresh() {
        return new EventQueue(EventQueue.Journal.NONE);
    }

    private static EventQueue withEvents(int n, long now) {
        EventQueue q = fresh();
        for (int i = 0; i < n; i++) {
            q.push(EventTypes.EVENT, "<event>第" + i + "件</event>", now, false);
        }
        return q;
    }

    // ---- 三条排空理由 ----

    @Test
    void urgentDrainsAtAnyLevel() {
        EventQueue q = fresh();
        q.push(EventTypes.EVENT, "<event>任务失败了</event>", T0, true);
        assertTrue(q.shouldDrain(T0, EventQueue.MAX_LEVEL), "档位拉到最沉默也拦不住急件");
    }

    @Test
    void enoughPilesUpDrains() {
        assertFalse(withEvents(2, T0).shouldDrain(T0, 5), "5 档要攒够 5 条");
        assertTrue(withEvents(5, T0).shouldDrain(T0, 5));
        assertTrue(withEvents(1, T0).shouldDrain(T0, 1), "1 档一有动静就说");
    }

    @Test
    void sittingTooLongDrainsBelowTheThreshold() {
        // 少了这条,10 档就退化成永久沉默:攒不够 10 件的话那几条会一直躺着
        EventQueue q = withEvents(3, T0);
        assertFalse(q.shouldDrain(T0 + 60_000L, 10), "才一分钟,再等等");
        assertTrue(q.shouldDrain(T0 + EventQueue.maxWaitMsOf(10), 10), "躺够了就得说");
    }

    @Test
    void emptyQueueNeverDrains() {
        EventQueue q = fresh();
        for (int lv = EventQueue.MIN_LEVEL; lv <= EventQueue.MAX_LEVEL; lv++) {
            assertFalse(q.shouldDrain(T0 + 999_999_999L, lv), "档位 " + lv);
        }
    }

    @Test
    void patienceGrowsWithTheLevel() {
        long prev = -1;
        for (int lv = EventQueue.MIN_LEVEL; lv <= EventQueue.MAX_LEVEL; lv++) {
            long wait = EventQueue.maxWaitMsOf(lv);
            assertTrue(wait > prev, "档位越高越沉得住气,档位 " + lv + " 却是 " + wait);
            prev = wait;
        }
        assertTrue(EventQueue.maxWaitMsOf(EventQueue.MAX_LEVEL) <= 60 * 60_000L, "再沉默也不超过一小时");
    }

    @Test
    void levelsAreClampedNotTrusted() {
        // 档位来自配置文件,主人可以手改成任何数
        assertEquals(EventQueue.MIN_LEVEL, EventQueue.clampLevel(0));
        assertEquals(EventQueue.MAX_LEVEL, EventQueue.clampLevel(999));
        assertEquals(EventQueue.MAX_LEVEL, EventQueue.thresholdOf(50), "越界也不能变成无限沉默");
    }

    // ---- 急件叫醒 ----

    @Test
    void urgentPushWakesListeners() {
        EventQueue q = fresh();
        java.util.concurrent.atomic.AtomicInteger woken = new java.util.concurrent.atomic.AtomicInteger();
        q.addUrgentListener(woken::incrementAndGet);

        q.push(EventTypes.EVENT, "<event>下雨了</event>", T0, false);
        assertEquals(0, woken.get(), "普通件不叫");

        q.push(EventTypes.QUERY, "<query>救命</query>", T0, true);
        assertEquals(1, woken.get(), "急件即叫");
        assertTrue(q.hasUrgent(), "叫醒不递件,货还在台账上");
    }

    @Test
    void listenerMayRemoveItselfInCallback() {
        // get_events 的停靠者被叫醒即摘钩——回调里摘自己不能炸并发修改
        EventQueue q = fresh();
        java.util.concurrent.atomic.AtomicInteger woken = new java.util.concurrent.atomic.AtomicInteger();
        Runnable[] once = new Runnable[1];
        once[0] = () -> {
            woken.incrementAndGet();
            q.removeUrgentListener(once[0]);
        };
        q.addUrgentListener(once[0]);

        q.push(EventTypes.QUERY, "<query>a</query>", T0, true);
        q.push(EventTypes.QUERY, "<query>b</query>", T0, true);
        assertEquals(1, woken.get(), "摘了自己,第二声不再叫");
    }

    @Test
    void wakeIsSugarStateIsTruth() {
        // 脉冲可以丢,电平不会骗:没人停靠时急件落地,状态随时问得出来
        EventQueue q = fresh();
        q.push(EventTypes.QUERY, "<query>没人听见</query>", T0, true);

        assertTrue(q.hasUrgent());
        assertTrue(q.shouldDrain(T0, EventQueue.MAX_LEVEL), "有急件即熟,无关有没有人被叫醒");
        assertEquals(1, EventQueue.render(q.takeEntries(T0), T0).size());
    }

    // ---- 类型表(队列不认识类型) ----

    @Test
    void interruptClearsByTheTableNotByAnIf() {
        EventQueue q = fresh();
        q.push(EventTypes.QUERY, "<query>去挖铁矿</query>", T0, true);
        q.push(EventTypes.EVENT, "<event kind=\"death\">你死了</event>", T0, false);

        assertEquals(1, q.clearInterrupted(), "清掉被取代的指令");
        assertEquals(0, q.count(EventTypes.QUERY));
        assertEquals(1, q.count(EventTypes.EVENT), "事实不因为按了停止就没发生");
    }

    // ---- 先到先得 ----

    @Test
    void takeWhileStopsAtTheFirstEntryItCannotHandle() {
        // 有些条目到了安全点要做的不是"往 user 消息里添一段话"——整理记忆就是。
        // 排空按顺序走到它就停下:前面的先走完,它留在队首等下一次。
        EventQueue q = fresh();
        q.push(EventTypes.EVENT, "<event>她挨打了</event>", T0, false);
        q.push(EventTypes.QUERY, "<query>回来</query>", T0, true);
        q.push(EventTypes.COMPACT, "整理记忆", T0, true);
        q.push(EventTypes.QUERY, "<query>整理完再说这句</query>", T0, true);

        List<EventQueue.Entry> head =
                q.takeWhile(e -> !EventTypes.COMPACT.equals(e.type()), T0);

        assertEquals(2, head.size(), "整理之前排着的两条先走");
        assertEquals(2, q.size(), "整理和它后面那句原样留着");
        assertEquals(EventTypes.COMPACT, q.entries().get(0).type(), "整理现在是队首");
    }

    @Test
    void takeWhileReturnsNothingWhenTheHeadDoesNotMatch() {
        // 队首就是整理 —— 说明轮到它了,一条文本都不该被顺出去
        EventQueue q = fresh();
        q.push(EventTypes.COMPACT, "整理记忆", T0, true);
        q.push(EventTypes.QUERY, "<query>回来</query>", T0, true);

        assertTrue(q.takeWhile(e -> !EventTypes.COMPACT.equals(e.type()), T0).isEmpty());
        assertTrue(q.takeWhile(null, T0).isEmpty());
        assertEquals(2, q.size(), "什么都没取走");
    }

    @Test
    void backToBackCompactsCollapseIntoOne() {
        // 连着按了三次:它们是相邻的,合成一次不改变任何可观察的行为
        EventQueue q = fresh();
        for (int i = 0; i < 3; i++) q.push(EventTypes.COMPACT, "整理记忆", T0, true);
        q.push(EventTypes.QUERY, "<query>回来</query>", T0, true);

        assertEquals(3, q.takeWhile(e -> EventTypes.COMPACT.equals(e.type()), T0).size());
        assertEquals(1, q.size(), "后面那句还排着");
    }

    @Test
    void compactEntriesNeverReachTheModelAsText() {
        // toModel 回 null = "这条不是给模型看的文本",render 因此跳过它。
        // 这是表里<b>已有</b>的表达,不是为整理新造的概念。
        EventQueue q = fresh();
        q.push(EventTypes.COMPACT, "整理记忆", T0, true);
        q.push(EventTypes.QUERY, "<query>回来</query>", T0, true);

        assertEquals(List.of("<query>回来</query>"), EventQueue.render(q.takeEntries(T0), T0));
    }

    @Test
    void chatPreviewShowsOnlyWhatTheTableSaysToShow() {
        EventQueue q = fresh();
        q.push(EventTypes.EVENT, "<event>她挨打了</event>", T0, false);
        q.push(EventTypes.QUERY, "<query>回来</query>", T0, true);

        assertEquals(List.of("<query>回来</query>"), q.chatPreview(),
                "事件不进聊天流——那是表里写的,不是这儿判断的;"
                        + "进得来的原样交出去,画成什么样是渲染那一层的事");
    }

    @Test
    void anUnregisteredTypeFallsBackToRawInsteadOfVanishing() {
        // 注册漏了不该表现成静默丢数据
        EventQueue q = fresh();
        q.push("第三方模组的类型", "外面来的一条", T0, false);

        assertEquals(List.of("<events>\n外面来的一条\n</events>"), EventQueue.render(q.takeEntries(T0), T0));
    }

    @Test
    void thirdPartyTypesJustWork() {
        EventTypes.register(new EventTypes.Type("raid_alert",
                s -> "[袭击] " + s, s -> "⚔ " + s, false, false));
        EventQueue q = fresh();
        q.push("raid_alert", "村庄被围了", T0, true);

        assertTrue(q.shouldDrain(T0, EventQueue.MAX_LEVEL), "急不急看条目,不看类型");
        assertEquals(List.of("⚔ 村庄被围了"), q.chatPreview());
        assertEquals(List.of("<events>\n[袭击] 村庄被围了\n</events>"), EventQueue.render(q.takeEntries(T0), T0));
    }

    // ---- 排空 ----

    @Test
    void worldEventsComeFirstOwnerWordsLast() {
        // 模型该读到的顺序是“先看清发生了什么，再看主人要什么”——
        // 按入队顺序平铺的话，事件和 query 混着，得它自己从一串杂物里理时间线。
        EventQueue q = fresh();
        q.push(EventTypes.QUERY, "<query>先说的</query>", T0, true);
        q.push(EventTypes.EVENT, "<event>后到的</event>", T0, false);

        assertEquals(List.of("<events>\n<event>后到的</event>\n</events>",
                "<query>先说的</query>"), EventQueue.render(q.takeEntries(T0), T0));
        assertTrue(q.isEmpty(), "倒完就空");
    }

    @Test
    void eventsAreSortedByWhenTheyHappened() {
        // 入队顺序≠发生顺序：服务端离线出箱里攒的、死亡期间锁着攒下的，
        // 都是后来才进队的。模型要拿它们理因果，时间必须是对的。
        EventQueue q = fresh();
        q.push(EventTypes.EVENT, "<event>后发生的</event>", T0 + 5_000L, false);
        q.push(EventTypes.EVENT, "<event>先发生的</event>", T0, false);

        assertEquals(List.of("<events>\n<event>先发生的</event>\n"
                + "<event>后发生的</event>\n</events>"), EventQueue.render(q.takeEntries(T0 + 5_000L), T0 + 5_000L));
    }

    @Test
    void staleInputIsLabelledWithItsAge() {
        // 跨会话恢复的旧闻:模型该知道这是"主人不在时发生的",不能当成刚发生的去反应
        EventQueue q = fresh();
        q.push(EventTypes.QUERY, "<query>去挖铁矿</query>", T0, true);

        List<String> out = EventQueue.render(q.takeEntries(T0 + 3 * 3600_000L), T0 + 3 * 3600_000L);

        assertEquals(1, out.size());
        assertTrue(out.get(0).startsWith("[发生于约3小时前] "), "实际:" + out.get(0));
        assertTrue(out.get(0).endsWith("<query>去挖铁矿</query>"), "原文不许被改动");
    }

    @Test
    void freshInputIsNotLabelled() {
        EventQueue q = fresh();
        q.push(EventTypes.QUERY, "<query>刚说的</query>", T0, true);
        assertEquals(List.of("<query>刚说的</query>"), EventQueue.render(q.takeEntries(T0 + 60_000L), T0 + 60_000L), "十分钟内不标,免得吵");
    }

    // ---- 上限 ----

    @Test
    void overflowDropsTheOldestAndSaysSo() {
        // 锁可能开很久(外接大脑能开一整天),不设上限会把上下文撑爆;
        // 但丢弃不能无声无息——主人得知道自己看到的是全部还是残片
        EventQueue q = new EventQueue(EventQueue.Journal.NONE, 3);
        for (int i = 0; i < 5; i++) {
            q.push(EventTypes.EVENT, "<event>第" + i + "件</event>", T0, false);
        }

        List<String> out = EventQueue.render(q.takeEntries(T0), T0);

        assertEquals(1, out.size(), "全是世界的事，包成一块");
        String block = out.get(0);
        assertTrue(block.contains("第2件"), "丢的是最老的");
        assertFalse(block.contains("第1件"), "最老的两条该没了");
        assertTrue(block.contains("2 件事"), "丢了几条要说得出来:" + block);
    }

    @Test
    void dropNoteIsReportedOnceThenReset() {
        EventQueue q = new EventQueue(EventQueue.Journal.NONE, 1);
        q.push(EventTypes.EVENT, "<event>一</event>", T0, false);
        q.push(EventTypes.EVENT, "<event>二</event>", T0, false);
        assertEquals(1, q.droppedCount());

        EventQueue.render(q.takeEntries(T0), T0);
        assertEquals(0, q.droppedCount(), "报过一次就清零,不该次次重复");
    }

    // ---- 落盘 ----

    @Test
    void entriesSurviveAReload() {
        List<EventQueue.Entry> disk = new ArrayList<>();
        EventQueue.Journal journal = new EventQueue.Journal() {
            @Override public List<EventQueue.Entry> load() {
                return List.copyOf(disk);
            }

            @Override public void save(List<EventQueue.Entry> entries) {
                disk.clear();
                disk.addAll(entries);
            }
        };

        EventQueue q = new EventQueue(journal);
        q.push(EventTypes.EVENT, "<event>任务失败了</event>", T0, true);
        q.push(EventTypes.QUERY, "<query>在吗</query>", T0, true);

        EventQueue reopened = new EventQueue(journal);

        assertEquals(2, reopened.size());
        assertTrue(reopened.hasUrgent(), "重进游戏它还是急的");
        assertEquals(1, reopened.count(EventTypes.QUERY));
    }

    @Test
    void drainingEmptiesTheJournalToo() {
        List<EventQueue.Entry> disk = new ArrayList<>();
        EventQueue.Journal journal = new EventQueue.Journal() {
            @Override public List<EventQueue.Entry> load() {
                return List.copyOf(disk);
            }

            @Override public void save(List<EventQueue.Entry> entries) {
                disk.clear();
                disk.addAll(entries);
            }
        };
        EventQueue q = new EventQueue(journal);
        q.push(EventTypes.QUERY, "<query>喂</query>", T0, true);

        EventQueue.render(q.takeEntries(T0), T0);

        assertTrue(disk.isEmpty(), "消费过的输入不该留在账本里");
        assertTrue(new EventQueue(journal).isEmpty(), "重进游戏也不该再冒出来");
    }

    @Test
    void blankInputIsIgnored() {
        EventQueue q = fresh();
        q.push(EventTypes.QUERY, "", T0, true);
        q.push(EventTypes.QUERY, "   ", T0, true);
        q.push(EventTypes.QUERY, null, T0, true);
        assertTrue(q.isEmpty());
    }
}
