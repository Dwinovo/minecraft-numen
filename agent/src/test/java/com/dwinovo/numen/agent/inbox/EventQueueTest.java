package com.dwinovo.numen.agent.inbox;

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
 * 所以这套测试的重点是<b>它真的没有第四条</b>:不看谁发的、不看她在干嘛;急不急在入队那一刻
 * 由类型表和发送方定下,之后只认条目上的标记。
 */
class EventQueueTest {

    private static final long T0 = 1_000_000L;

    private static EventQueue fresh() {
        return new EventQueue(EventQueue.Journal.NONE);
    }

    private static EventQueue withEvents(int n, long now) {
        EventQueue q = fresh();
        for (int i = 0; i < n; i++) {
            q.push(EventTypes.TASK_FINISHED, "<event>第" + i + "件</event>", now, false);
        }
        return q;
    }

    // ---- 三条排空理由 ----

    @Test
    void urgentDrainsAtAnyLevel() {
        EventQueue q = fresh();
        q.push(EventTypes.TASK_FINISHED, "<event>任务失败了</event>", T0, true);
        assertTrue(q.ripeness(T0, EventQueue.MAX_LEVEL).ripe(), "档位拉到最沉默也拦不住急件");
    }

    @Test
    void enoughPilesUpDrains() {
        assertFalse(withEvents(2, T0).ripeness(T0, 5).ripe(), "5 档要攒够 5 条");
        assertTrue(withEvents(5, T0).ripeness(T0, 5).ripe());
        assertTrue(withEvents(1, T0).ripeness(T0, 1).ripe(), "1 档一有动静就说");
    }

    @Test
    void sittingTooLongDrainsBelowTheThreshold() {
        // 少了这条,10 档就退化成永久沉默:攒不够 10 件的话那几条会一直躺着
        EventQueue q = withEvents(3, T0);
        assertFalse(q.ripeness(T0 + 60_000L, 10).ripe(), "才一分钟,再等等");
        assertTrue(q.ripeness(T0 + EventQueue.maxWaitMsOf(10), 10).ripe(), "躺够了就得说");
    }

    @Test
    void emptyQueueNeverDrains() {
        EventQueue q = fresh();
        for (int lv = EventQueue.MIN_LEVEL; lv <= EventQueue.MAX_LEVEL; lv++) {
            assertFalse(q.ripeness(T0 + 999_999_999L, lv).ripe(), "档位 " + lv);
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

        q.push(EventTypes.TASK_FINISHED, "<event>下雨了</event>", T0, false);
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
        assertTrue(q.ripeness(T0, EventQueue.MAX_LEVEL).ripe(), "有急件即熟,无关有没有人被叫醒");
        assertEquals(1, EventQueue.render(q.takeEntries(T0), T0).size());
    }

    // ---- 类型表(队列不认识类型) ----

    @Test
    void interruptClearsByTheTableNotByAnIf() {
        EventQueue q = fresh();
        q.push(EventTypes.QUERY, "<query>去挖铁矿</query>", T0, true);
        q.push(EventTypes.DEATH, "<event kind=\"death\">你死了</event>", T0, false);

        assertEquals(1, q.clearInterrupted(), "清掉被取代的指令");
        assertEquals(0, q.count(EventTypes.QUERY));
        assertEquals(1, q.count(EventTypes.DEATH), "事实不因为按了停止就没发生");
    }

    // ---- 先到先得 ----

    @Test
    void aCallTakesNothingPastTheFirstControlEntry() {
        // 有些条目到了安全点要做的不是"往 user 消息里添一段话"——整理记忆就是。
        // 调模型取件走到它就停下:前面的先走完,它和它后面的留着等它执行。
        EventQueue q = fresh();
        q.push(EventTypes.TASK_FINISHED, "<event>她挨打了</event>", T0, false);
        q.push(EventTypes.QUERY, "<query>回来</query>", T0, true);
        q.push(EventTypes.COMPACT, "整理记忆", T0, true);
        q.push(EventTypes.QUERY, "<query>整理完再说这句</query>", T0, true);

        List<EventQueue.Entry> head = q.takeForCall(false, T0);

        assertEquals(2, head.size(), "整理之前排着的两条先走");
        assertEquals(2, q.size(), "整理和它后面那句原样留着");
        assertEquals(EventTypes.COMPACT, q.entries().get(0).type(), "整理现在是队首");
    }

    @Test
    void aCallTakesNothingWhenTheHeadIsAControlEntry() {
        // 队首就是整理 —— 说明轮到它了,一条文本都不该被顺出去
        EventQueue q = fresh();
        q.push(EventTypes.COMPACT, "整理记忆", T0, true);
        q.push(EventTypes.QUERY, "<query>回来</query>", T0, true);

        assertFalse(q.wantsAnswer(), "墙之前没有要她回应的");
        assertTrue(q.takeForCall(false, T0).isEmpty());
        assertTrue(q.takeForCall(true, T0).isEmpty(), "本来就要调模型也不越过墙");
        assertEquals(2, q.size(), "什么都没取走");
    }

    @Test
    void takeTextSkipsControlEntriesAndLeavesThemQueued() {
        // 外接模型取件:队首的整理是对内脑说的,留着;排在它后面的话照取,不能被它挡住
        EventQueue q = fresh();
        q.push(EventTypes.COMPACT, "整理记忆", T0, false);
        q.push(EventTypes.QUERY, "<query>回来</query>", T0, false);
        q.push(EventTypes.CLEAR, "清空上下文", T0, false);
        q.push(EventTypes.TASK_FINISHED, "<event>她挨打了</event>", T0, false);

        List<EventQueue.Entry> text = q.takeText(T0);

        assertEquals(List.of(EventTypes.QUERY, EventTypes.TASK_FINISHED),
                text.stream().map(EventQueue.Entry::type).toList(), "文本全取走,按入队顺序");
        assertEquals(List.of(EventTypes.COMPACT, EventTypes.CLEAR),
                q.entries().stream().map(EventQueue.Entry::type).toList(), "控制条目原样留着,先后不变");
        assertTrue(q.takeText(T0).isEmpty(), "只剩控制条目就什么都不动");
        assertEquals(2, q.size());
    }

    @Test
    void aCallThatHappensAnywayLeavesFollowUpsAndEverythingPastTheWall() {
        // 工具结果等着回应的边界:插话照取,接续留着不挡路,控制条目之后的等它执行完
        EventQueue q = fresh();
        q.push(EventTypes.TASK_FINISHED, "<event>挖到铁了</event>", T0, false);
        q.push(EventTypes.GOAL, "<goal-progress>还差</goal-progress>", T0, false);
        q.push(EventTypes.QUERY, "<query>先回来</query>", T0, false);
        q.push(EventTypes.CLEAR, "清空上下文", T0, false);
        q.push(EventTypes.QUERY, "<query>清完再说这句</query>", T0, false);

        List<EventQueue.Entry> steer = q.takeForCall(true, T0);

        assertEquals(List.of("<event>挖到铁了</event>", "<query>先回来</query>"),
                steer.stream().map(EventQueue.Entry::text).toList(), "墙之前的插话按入队顺序取走");
        assertEquals(List.of(EventTypes.GOAL, EventTypes.CLEAR, EventTypes.QUERY),
                q.entries().stream().map(EventQueue.Entry::type).toList(), "接续、墙和墙之后的原样留着");
        assertEquals(List.of(EventTypes.GOAL), q.takeForCall(false, T0).stream().map(EventQueue.Entry::type).toList(),
                "本来要停时,只剩接续就是它引起这次调用");
        assertEquals(2, q.size());
    }

    @Test
    void backToBackCompactsCollapseIntoOne() {
        // 连着按了三次:它们是相邻的,合成一次不改变任何可观察的行为
        EventQueue q = fresh();
        for (int i = 0; i < 3; i++) q.push(EventTypes.COMPACT, "整理记忆", T0, true);
        q.push(EventTypes.QUERY, "<query>回来</query>", T0, true);

        assertEquals(3, q.nextControls().size(), "只看不取");
        assertEquals(3, q.takeControls().size());
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
        q.push(EventTypes.TASK_FINISHED, "<event>她挨打了</event>", T0, false);
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
                s -> "[袭击] " + s, s -> "⚔ " + s, false, false, EventTypes.Delivery.STEER, false));
        EventQueue q = fresh();
        q.push("raid_alert", "村庄被围了", T0, true);

        assertTrue(q.ripeness(T0, EventQueue.MAX_LEVEL).ripe(), "这类不恒急,发送方标了急就是急件");
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
        q.push(EventTypes.TASK_FINISHED, "<event>后到的</event>", T0, false);

        assertEquals(List.of("<events>\n<event>后到的</event>\n</events>",
                "<query>先说的</query>"), EventQueue.render(q.takeEntries(T0), T0));
        assertTrue(q.isEmpty(), "倒完就空");
    }

    @Test
    void eventsAreSortedByWhenTheyHappened() {
        // 入队顺序≠发生顺序：服务端离线出箱里攒的、死亡期间锁着攒下的，
        // 都是后来才进队的。模型要拿它们理因果，时间必须是对的。
        EventQueue q = fresh();
        q.push(EventTypes.TASK_FINISHED, "<event>后发生的</event>", T0 + 5_000L, false);
        q.push(EventTypes.TASK_FINISHED, "<event>先发生的</event>", T0, false);

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
            q.push(EventTypes.TASK_FINISHED, "<event>第" + i + "件</event>", T0, false);
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
        q.push(EventTypes.TASK_FINISHED, "<event>一</event>", T0, false);
        q.push(EventTypes.TASK_FINISHED, "<event>二</event>", T0, false);
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
        q.push(EventTypes.TASK_FINISHED, "<event>任务失败了</event>", T0, true);
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

    // ---- 捎带(AMBIENT):听得见,但不为它开一轮 ----

    /** 群聊的不变量守在这一处:发送方怎么标,旁听到的话都不是急件。 */
    @Test
    void overheardTalkIsNeverUrgentNoMatterWhatTheSenderSays() {
        EventQueue q = fresh();
        assertFalse(q.push(EventTypes.TALK, "<event kind=\"talk\">[阿岚] 我去东边</event>", T0, true),
                "捎带的条目不可能是急件");
        assertFalse(q.ripeness(T0, 1).ripe(), "主动性拉到最高也不该为一句旁听开一轮");
    }

    /** 攒多少条、躺多久都不算数——熟度只数那些本来就该叫醒她的。 */
    @Test
    void overheardTalkNeverRipens() {
        EventQueue q = fresh();
        for (int i = 0; i < EventQueue.thresholdOf(1) * 3; i++) {
            q.push(EventTypes.TALK, "<event kind=\"talk\">[阿岚] 第" + i + "句</event>", T0, false);
        }
        assertFalse(q.ripeness(T0, 1).ripe(), "条数再多也不开轮");
        assertFalse(q.ripeness(T0 + EventQueue.maxWaitMsOf(1) * 10, 1).ripe(), "躺再久也不开轮");
    }

    /** 但别的事把她叫醒时,躺着的旁听跟着那一轮一起走——这就是它免费的原因。 */
    @Test
    void overheardTalkRidesAlongOnSomeoneElsesWakeUp() {
        EventQueue q = fresh();
        q.push(EventTypes.TALK, "<event kind=\"talk\">[阿岚] 我去东边</event>", T0, false);
        assertFalse(q.ripeness(T0, 1).ripe());

        q.push(EventTypes.QUERY, "<query>回来吃饭</query>", T0 + 1, false);
        assertTrue(q.ripeness(T0 + 1, 1).ripe(), "主人说话了,这一轮该开");

        List<String> out = EventQueue.render(q.takeForCall(false, T0 + 1), T0 + 1);
        assertTrue(String.join("\n", out).contains("[阿岚] 我去东边"), "旁听到的话要跟着这一轮进去");
        assertTrue(q.isEmpty(), "循环取件时带走了它,不会躺在队里越攒越多");
    }

    /** 本来就要调模型的边界(工具结果等着回应)上,旁听同样捎带进去。 */
    @Test
    void overheardTalkRidesAlongOnACallThatHappensAnyway() {
        EventQueue q = fresh();
        q.push(EventTypes.TALK, "<event kind=\"talk\">[阿岚] 我去东边</event>", T0, false);

        assertEquals(List.of(EventTypes.TALK), q.takeForCall(true, T0).stream().map(EventQueue.Entry::type).toList());
    }

    /** 本来要停的时候,只剩旁听不是再调一次模型的理由——那就是同伴唤醒了同伴。 */
    @Test
    void overheardTalkAloneCausesNoCall() {
        EventQueue q = fresh();
        q.push(EventTypes.TALK, "<event kind=\"talk\">[阿岚] 在吗</event>", T0, false);

        assertFalse(q.wantsAnswer());
        assertTrue(q.takeForCall(false, T0).isEmpty(), "不调模型就一条都不取");
        assertEquals(1, q.size(), "留着等别的事叫醒她");
    }

    /** 队里只剩旁听时不是"空",只是不值得开轮——别让它被当成没有东西可取。 */
    @Test
    void aQueueHoldingOnlyOverheardTalkIsStillNotEmpty() {
        EventQueue q = fresh();
        q.push(EventTypes.TALK, "<event kind=\"talk\">[阿岚] 在吗</event>", T0, false);
        assertFalse(q.isEmpty());
        assertFalse(q.ripeness(T0, 1).ripe());
        assertFalse(q.hasWaking(), "它开不起一次 run");
    }

    // ---- 控制条目不叫醒她,也就谈不上急 ----

    @Test
    void controlEntriesAreNeverUrgentNoMatterWhatTheSenderSays() {
        EventQueue q = fresh();
        java.util.concurrent.atomic.AtomicInteger woken = new java.util.concurrent.atomic.AtomicInteger();
        q.addUrgentListener(woken::incrementAndGet);

        assertFalse(q.push(EventTypes.CLEAR, "清空上下文", T0, true));
        assertFalse(q.push(EventTypes.COMPACT, "整理记忆", T0, true));

        assertFalse(q.hasUrgent());
        assertFalse(q.ripeness(T0, EventQueue.MIN_LEVEL).ripe(), "控制命令不是开 run 的理由");
        assertFalse(q.hasWaking());
        assertEquals(0, woken.get(), "外接大脑的长轮询不为它醒");
    }

    /** 盘上的急件标记是写下它的那一版定的:读回来同样过入队那一条规则。 */
    @Test
    void entriesReadBackPassTheSameUrgencyRuleAsPush() {
        List<EventQueue.Entry> disk = List.of(
                new EventQueue.Entry(EventTypes.TALK, "<event kind=\"talk\">[阿岚] 在吗</event>", T0, true),
                new EventQueue.Entry(EventTypes.CLEAR, "清空上下文", T0 + 1, true),
                new EventQueue.Entry(EventTypes.TASK_FINISHED, "<event>任务失败了</event>", T0 + 2, true),
                new EventQueue.Entry(EventTypes.QUERY, "<query>在吗</query>", T0 + 3, false));
        EventQueue q = new EventQueue(new EventQueue.Journal() {
            @Override public List<EventQueue.Entry> load() {
                return disk;
            }

            @Override public void save(List<EventQueue.Entry> entries) {
            }
        });

        assertEquals(List.of(false, false, true, true), q.entries().stream().map(EventQueue.Entry::urgent).toList(),
                "旁听与控制命令不急;世界的事照发送方;主人的话恒急");
    }
}
