package com.dwinovo.numen.chat;

import com.dwinovo.numen.client.chat.OwnerWordsMode;
import com.dwinovo.numen.event.EventQueue;
import com.dwinovo.numen.event.EventTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队列<b>发</b>什么记号,面板就得<b>剥</b>掉什么记号。
 *
 * <h2>为什么把这两边绑在一条测试里</h2>
 * 它们住在两个模块、各写各的:{@link EventQueue#drain} 拼协议记号,
 * {@link OwnerWordsMode} 用一串正则把记号剥掉只留主人的原话。
 * 加一个新标签只改一边,另一边不会报错——只会在面板上漏出半截尖括号。
 *
 * <p>真发生过:给事件加了 {@code <events>} 分组包装,而剥的那条规则是
 * {@code <event\b}——"events" 在 t 与 s 之间没有词边界,整个包装漏掉了,
 * 主人看到的是字面的 {@code <events></events>} 两行,还以为模组在发空事件。
 *
 * <p>所以这里不手写样本,<b>直接拿队列真实倒出来的东西喂给过滤器</b>。
 */
class InjectedMarkupIsStrippedTest {

    private static final long T0 = 1_700_000_000_000L;

    private static String render(EventQueue q) {
        return String.join("\n", EventQueue.render(q.takeEntries(T0), T0));
    }

    @Test
    void goalInjectionsShowNothingToTheOwner() {
        // 目标那两块也是客户端注入的:设定时那份指令、续跑时那句"还差什么"。
        // 漏了就是一整块指令(连"别自己宣布做完"那几句)当成主人的气泡糊在面板上。
        var goal = com.dwinovo.numen.agent.goal.GoalState.of("挖 64 个铁锭", T0);
        EventQueue q = new EventQueue(EventQueue.Journal.NONE);
        q.push(EventTypes.GOAL,
                com.dwinovo.numen.agent.goal.GoalPrompts.initialDirective(goal), T0, true);

        assertTrue(new OwnerWordsMode().userText(render(q)).isEmpty(), "设定那块漏出来了");

        goal.countTurn();
        q.push(EventTypes.GOAL,
                com.dwinovo.numen.agent.goal.GoalPrompts.progress("只挖到 30 个", goal, T0),
                T0, true);

        assertTrue(new OwnerWordsMode().userText(render(q)).isEmpty(), "续跑那句漏出来了");
    }

    @Test
    void ownerWordsSurviveAGoalInjectionInTheSameBatch() {
        // 同一条消息里既有目标注入又有主人的话:剥掉前者,后者一个字不能少
        var goal = com.dwinovo.numen.agent.goal.GoalState.of("挖 64 个铁锭", T0);
        EventQueue q = new EventQueue(EventQueue.Journal.NONE);
        q.push(EventTypes.GOAL,
                com.dwinovo.numen.agent.goal.GoalPrompts.progress("只挖到 30 个", goal, T0), T0, true);
        q.push(EventTypes.QUERY, "<query>先回来一下</query>", T0, true);

        assertEquals("先回来一下", new OwnerWordsMode().userText(render(q)));
    }

    @Test
    void aPureEventBatchShowsNothingToTheOwner() {
        // 全是世界发生的事,主人一个字都没说 —— 面板上不该出现任何东西
        EventQueue q = new EventQueue(EventQueue.Journal.NONE);
        q.push(EventTypes.EVENT, "<event kind=\"death\" day=\"0\" t=\"06:43\">你刚才死了</event>", T0, true);
        q.push(EventTypes.EVENT, "<event kind=\"body_log\" day=\"0\" t=\"06:44\">吃了个面包</event>", T0, false);

        String shown = new OwnerWordsMode().userText(render(q));

        assertTrue(shown.isEmpty(), "面板上漏出了协议记号:" + shown);
    }

    @Test
    void ownerWordsSurviveEventsAroundThem() {
        EventQueue q = new EventQueue(EventQueue.Journal.NONE);
        q.push(EventTypes.EVENT, "<event kind=\"body_log\" day=\"0\" t=\"06:44\">吃了个面包</event>", T0, false);
        q.push(EventTypes.QUERY, "<query>你在干嘛</query>", T0, true);

        assertEquals("你在干嘛", new OwnerWordsMode().userText(render(q)));
    }

    @Test
    void noStrayAngleBracketsSurviveAnything() {
        // 兜底:不论队列里装的是什么组合,剥完都不该剩下尖括号
        EventQueue q = new EventQueue(EventQueue.Journal.NONE);
        q.push(EventTypes.EVENT, "<event kind=\"task_finished\" id=\"t1\">挖完了</event>", T0, false);
        q.push(EventTypes.EVENT, "<event kind=\"dimension_change\"/>", T0, false);
        q.push(EventTypes.QUERY, "<query>好</query>", T0, true);

        String shown = new OwnerWordsMode().userText(render(q));

        assertEquals("好", shown);
        assertTrue(shown.indexOf('<') < 0 && shown.indexOf('>') < 0, shown);
    }

    @Test
    void anEmptyQueueRendersToNothing() {
        assertEquals(List.of(), EventQueue.render(new EventQueue(EventQueue.Journal.NONE).takeEntries(T0), T0));
    }
}
