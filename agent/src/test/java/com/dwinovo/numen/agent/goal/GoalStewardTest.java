package com.dwinovo.numen.agent.goal;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.loop.HaltReason;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.LoopHarness;
import com.dwinovo.numen.agent.loop.ModelOutcome;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期目标挂在一个真的循环内核上:一次 run 说完才评估,评估不是 run,没做完就接着推一条续跑,
 * 做完、卡住、主人按停止就收工。
 */
class GoalStewardTest extends LoopHarness {

    private final List<GoalState> persisted = new ArrayList<>();
    private boolean bodyBusy;
    private GoalSteward goals;

    @BeforeEach
    void setUpGoals() {
        goals = new GoalSteward("test", loop, transcript, inbox, () -> "<runtime_state/>", () -> bodyBusy,
                persisted::add, null);
        loop.subscribe(goals::on);
    }

    /** 主人定了目标,她答了一句——一次 run 就这么说完了。 */
    private GoalState goalSetAndFirstRunDone(String objective) {
        GoalState goal = GoalState.of(objective, T0);
        assertTrue(goals.set(goal), "活着、没被外接驾驶:当场交给她");
        loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY,
                "<query>/goal " + objective + "</query>\n" + GoalPrompts.initialDirective(goal), 0, false)));
        model.last().say("好,这就去");
        return goal;
    }

    private boolean isEvaluation(Call call) {
        return GoalPrompts.evaluatorSystem().equals(call.request().systemPrompt());
    }

    private void verdict(String line) {
        model.last().onDone().accept(new ModelOutcome.Answered(new AssistantTurn(line, List.of(), null),
                new Usage(100, 20, 0, 0)));
    }

    @Test
    void aFinishedRunIsJudgedByASeparateCallThatIsNotARun() {
        goalSetAndFirstRunDone("挖 64 个铁");

        assertTrue(isEvaluation(model.last()), "说完之后另开一次调用来判");
        assertTrue(model.last().request().tools().isEmpty(), "判的人不带工具");
        assertNull(loop.status().phase(), "评估不占内核");
        assertTrue(model.last().lastUser().contains("挖 64 个铁"));
    }

    @Test
    void notMetYetPushesAContinuationThatStartsTheNextRun() {
        GoalState goal = goalSetAndFirstRunDone("挖 64 个铁");
        int callsBefore = model.calls.size();

        verdict(GoalPrompts.NOT_MET + ": 背包里只有 20 个铁");

        assertEquals(callsBefore + 1, model.calls.size(), "续跑是急件,当场开下一次 run");
        assertFalse(isEvaluation(model.last()));
        assertTrue(model.last().lastUser().contains("背包里只有 20 个铁"), "只补评估器那句还差什么");
        assertEquals(2, goal.turnsExecuted());
        assertEquals(goal, persisted.get(persisted.size() - 1), "续跑一轮就落盘一次");
    }

    @Test
    void metEndsTheGoalQuietly() {
        goalSetAndFirstRunDone("挖 64 个铁");
        int callsBefore = model.calls.size();

        verdict(GoalPrompts.MET + ": 背包里有 64 个铁锭");

        assertNull(goals.goal());
        assertNull(persisted.get(persisted.size() - 1), "收工也落盘");
        assertEquals(callsBefore, model.calls.size(), "收工不再开 run");
    }

    @Test
    void theJudgeWaitsWhileTheBodyIsStillWorking() {
        bodyBusy = true;
        goalSetAndFirstRunDone("挖 64 个铁");

        assertFalse(isEvaluation(model.last()), "身体还在挖:每个往返问一次挖完没有只是烧 token");
    }

    @Test
    void theJudgeWaitsWhenSomethingElseIsQueued() {
        GoalState goal = GoalState.of("挖 64 个铁", T0);
        goals.set(goal);
        loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY, "<query>先过来</query>", 0, false)));
        control(EventTypes.COMPACT);   // run 里主人按了整理:它排在队首,这次 run 说完就走
        model.last().say("来了");

        assertFalse(model.calls.stream().anyMatch(this::isEvaluation),
                "队里还排着东西,那本来就会接着发生,做完再判");
        assertEquals(com.dwinovo.numen.agent.loop.Phase.COMPACT, loop.status().phase());
    }

    @Test
    void aNewRunVoidsTheJudgementInFlight() {
        goalSetAndFirstRunDone("挖 64 个铁");
        Call judging = model.last();

        loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY, "<query>等等</query>", 0, false)));

        assertTrue(judging.cancel().isCancelled(), "判的已经不是眼前的局面了");
    }

    @Test
    void stopEndsTheGoal() {
        goalSetAndFirstRunDone("挖 64 个铁");

        loop.halt(HaltReason.OWNER_STOP);

        assertNull(goals.goal(), "按了停止还自己续上的话,停止键就成了摆设");
    }

    @Test
    void conversationUsageIsBilledToTheGoal() {
        GoalState goal = GoalState.of("挖 64 个铁", T0);
        goals.set(goal);

        goals.on(new LoopEvent.ModelUsed(new Usage(1000, 100, 0, 0), LoopEvent.Purpose.TURN));
        goals.on(new LoopEvent.ModelUsed(new Usage(9999, 999, 0, 0), LoopEvent.Purpose.COMPACT));

        assertEquals(1100, goal.tokensUsed(), "整理记忆不是为这个目标花的");
    }

    @Test
    void aGoalSetWhileDeadIsKeptButNotHandedOver() {
        loop.halt(HaltReason.DEATH);
        GoalState goal = GoalState.of("挖 64 个铁", T0);

        assertFalse(goals.set(goal), "死着的时候交出去也只会躺着");
        assertEquals(goal, goals.goal());
        assertEquals(goal, persisted.get(persisted.size() - 1));
    }

    @Test
    void theJudgeReadsBackToTheGoalDirectiveAndNoFurther() {
        transcript.addUser("<query>很早以前的一句</query>");
        goalSetAndFirstRunDone("挖 64 个铁");

        String query = model.last().lastUser();
        assertTrue(query.contains("好,这就去"));
        assertFalse(query.contains("很早以前的一句"), "目标设定之前的事跟这个目标无关");
    }

    @Test
    void theDirectiveIsRecognisedByTheSameMarkThatWritesIt() {
        GoalState goal = GoalState.of("挖 64 个铁", T0);
        assertTrue(GoalPrompts.isDirective(GoalPrompts.initialDirective(goal)));
        assertFalse(GoalPrompts.isDirective(GoalPrompts.progress("还差", goal, T0)));
        assertFalse(GoalPrompts.isDirective(new ConvoState.Msg.User("挖 64 个铁").content()));
    }
}
