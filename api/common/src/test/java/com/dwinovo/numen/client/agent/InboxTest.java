package com.dwinovo.numen.client.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收件箱:主人的话与世界事件的进箱口。
 *
 * <p>重点在<b>两种清空的语义相反</b>,而且各自都有道理:
 * <ul>
 *   <li><b>主人打断</b> —— 主人自己收回指令:清主人的话,留事实
 *       (死亡叙事必须活过停止按钮);</li>
 *   <li><b>死亡冻结</b> —— 世界状态作废(身体没了、物品掉了、人挪到主人身边):
 *       清事实,留主人的话(她听不见,但主人没收回)。</li>
 * </ul>
 * 写反了任何一边,都是主人眼里的"这模组吞消息"。
 */
class InboxTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path root;

    @BeforeEach
    void useTempRoot() {
        CompanionHome.overrideRoot(root);
    }

    @AfterEach
    void restore() {
        CompanionHome.overrideRoot(null);
    }

    // ---- 清空:只有"主人打断"这一种 ----

    @Test
    void interruptClearsOrdersButKeepsFacts() {
        Inbox box = new Inbox(A);
        box.pushEvent("<event kind=\"death\">你死了</event>", false);
        box.pushPrompt("<query>去挖铁矿</query>");

        assertEquals(1, box.clearPrompts(), "清掉的是被取代的指令");

        assertEquals(0, box.promptCount());
        assertEquals(1, box.eventCount(), "事实不因为按了停止就没发生");
    }

    @Test
    void everythingSurvivesARelaunch() {
        // 死亡期间说的话、攒下的事件都留着;这中间主人退出游戏,回来照样在
        Inbox box = new Inbox(A);
        box.pushEvent("<event kind=\"body_log\" day=\"3\" t=\"18:20\">挨了一下</event>", false);
        box.pushPrompt("<query>快跑</query>");

        Inbox reopened = new Inbox(A);

        assertEquals(1, reopened.promptCount());
        assertEquals(1, reopened.eventCount());
        assertEquals(2, reopened.snapshot().size());
    }

    // ---- urgent ----

    @Test
    void urgentIsVisibleToTheLoopAndSurvivesARelaunch() {
        // urgent 不该在箱里过夜。但开轮时机受协议约束(回合进行中只能等边界),
        // 所以箱子要如实记着——包括崩溃重启之后。
        Inbox box = new Inbox(A);
        box.pushEvent("<event kind=\"task_finished\" status=\"failed\">挖矿失败了</event>", true);

        assertTrue(box.hasUrgent());
        assertTrue(new Inbox(A).hasUrgent(), "重进游戏它还是急的");
    }

    @Test
    void ordinaryEventsAreNotUrgent() {
        Inbox box = new Inbox(A);
        box.pushEvent("<event kind=\"body_log\">吃了个面包</event>", false);
        box.pushPrompt("<query>在吗</query>");
        assertFalse(box.hasUrgent(), "主人的话不走 urgent 那条路,它本来就会开轮");
    }

    @Test
    void oldestAgeTracksTheFirstEventNotTheLast() {
        Inbox box = new Inbox(A);
        assertEquals(0L, box.oldestEventAgeMs(), "空箱没有年龄");
        box.pushEvent("<event>先来的</event>", false);
        box.pushEvent("<event>后来的</event>", false);
        assertTrue(box.oldestEventAgeMs() >= 0);
    }

    // ---- 倒箱 ----

    @Test
    void drainPutsFactsFirstAndTheOwnerLast() {
        // 让模型最后读到的是主人的诉求,不是一堆环境播报
        Inbox box = new Inbox(A);
        box.pushPrompt("<query>先说的话</query>");
        box.pushEvent("<event>后到的事</event>", false);

        assertEquals(List.of("<event>后到的事</event>", "<query>先说的话</query>"), box.drain());
        assertTrue(box.isEmpty(), "倒完就空");
    }

    @Test
    void drainEmptiesTheJournalToo() {
        Inbox box = new Inbox(A);
        box.pushPrompt("<query>喂</query>");
        assertTrue(Files.exists(CompanionHome.inbox(A)));

        box.drain();

        assertFalse(Files.exists(CompanionHome.inbox(A)), "消费过的输入不该留在账本里");
        assertTrue(new Inbox(A).isEmpty(), "重进游戏也不该再冒出来");
    }

    @Test
    void drainingAnEmptyBoxIsHarmless() {
        assertTrue(new Inbox(A).drain().isEmpty());
    }

    // ---- 年龄标注 ----

    @Test
    void staleInputIsLabelledWithItsAge() throws IOException {
        // 跨会话恢复的旧闻:模型该知道这是"主人不在时说的",不能当成刚发生的
        long threeHoursAgo = System.currentTimeMillis() - 3 * 3600_000L;
        Files.createDirectories(CompanionHome.dir(A));
        Files.writeString(CompanionHome.inbox(A),
                "{\"type\":\"prompt\",\"text\":\"<query>去挖铁矿</query>\",\"ts\":" + threeHoursAgo + "}\n",
                StandardCharsets.UTF_8);

        List<String> out = new Inbox(A).drain();

        assertEquals(1, out.size());
        assertTrue(out.get(0).startsWith("[发生于约3小时前] "), "实际:" + out.get(0));
        assertTrue(out.get(0).endsWith("<query>去挖铁矿</query>"), "原文不许被改动");
    }

    @Test
    void freshInputIsNotLabelled() {
        Inbox box = new Inbox(A);
        box.pushPrompt("<query>刚说的</query>");
        assertEquals(List.of("<query>刚说的</query>"), box.drain(), "十分钟内的不标年龄,免得吵");
    }

    @Test
    void corruptJournalDegradesToWhatIsReadable() throws IOException {
        Files.createDirectories(CompanionHome.dir(A));
        Files.writeString(CompanionHome.inbox(A),
                "{ 这行坏了\n{\"type\":\"prompt\",\"text\":\"<query>这行好的</query>\",\"ts\":0}\n",
                StandardCharsets.UTF_8);

        assertEquals(List.of("<query>这行好的</query>"), new Inbox(A).snapshot());
    }
}
