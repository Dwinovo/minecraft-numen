package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.llm.ProtocolView;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.provider.Usage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 循环内核的行为。假端口与喂回调的小工具在 {@link LoopHarness}。 */
class AgentLoopTest extends LoopHarness {

    // ---- 开 run 的时机 ----

    @Nested
    class WhenARunStarts {

        @Test
        void idleUrgentStartsARunRightAway() {
            ownerSays("过来");

            assertEquals(1, model.calls.size());
            assertTrue(model.last().lastUser().contains("<query>过来</query>"));
            assertEquals(1, eventsOf(LoopEvent.RunStarted.class).size());
            assertTrue(eventsOf(LoopEvent.TurnStarted.class).get(0).ownerSpoke(), "主人开口:语音硬停上一轮");
            assertEquals(Phase.MODEL, loop.status().phase());
        }

        @Test
        void ambientEventsWaitForCountOrAge() {
            worldEvent("刮风了", false);
            worldEvent("下雨了", false);
            assertTrue(model.calls.isEmpty(), "3 档要攒够 3 条");
            worldEvent("打雷了", false);
            assertEquals(1, model.calls.size(), "攒够了");
            String injected = model.last().lastUser();
            assertTrue(injected.contains("刮风了") && injected.contains("打雷了"), "攒的一次全带走");
            assertFalse(eventsOf(LoopEvent.TurnStarted.class).get(0).ownerSpoke(), "世界的事不算主人开口");
        }

        @Test
        void aLonelyEventGoesOutOnceItHasWaitedLongEnough() {
            worldEvent("鸡下蛋了", false);
            loop.tick();
            assertTrue(model.calls.isEmpty());

            host.now = T0 + EventQueue.maxWaitMsOf(host.level);
            loop.tick();

            assertEquals(1, model.calls.size(), "躺够了就开 run");
        }

        @Test
        void tickingWhileNothingIsRipeProducesNothing() {
            worldEvent("鸡下蛋了", false);
            events.clear();
            for (int i = 0; i < 1000; i++) {
                loop.tick();
            }
            assertTrue(model.calls.isEmpty());
            assertTrue(events.isEmpty(), "每 tick 推进一次不该有任何输出");
        }

        @Test
        void preambleRidesInFrontOfTheInjectedMessage() {
            host.preamble = "<known_blocks>chest 1 2 3</known_blocks>";
            ownerSays("箱子在哪");

            String injected = model.last().lastUser();
            assertTrue(injected.startsWith("<known_blocks>"), "现场块垫在最前");
            assertTrue(injected.endsWith("<query>箱子在哪</query>"), "主人的话垫底");
        }
    }

    // ---- 停牌 ----

    @Nested
    class Holds {

        @Test
        void afterStopUrgentInputDoesNotStartARunOrSayAnything() {
            ownerSays("去挖矿");
            loop.halt(HaltReason.OWNER_STOP);
            assertEquals(Hold.OWNER_STOP, loop.hold());
            events.clear();

            worldEvent("任务结束了", true);
            for (int i = 0; i < 200; i++) {
                loop.tick();
            }

            assertEquals(1, model.calls.size(), "停牌时急件不开 run");
            assertTrue(events.isEmpty(), "停牌时不刷任何输出");

            ownerSays("回来吧");

            assertNull(loop.hold(), "主人说话解开停止");
            assertEquals(2, model.calls.size());
            String injected = model.last().lastUser();
            assertTrue(injected.contains("任务结束了") && injected.contains("<query>回来吧</query>"),
                    "停牌期间收下的事件跟着主人的话一起走");
        }

        @Test
        void blockedEndpointKeepsTheWordsQueuedAndSaysWhyOnce() {
            model.unavailable = "没绑模型";
            ownerSays("你好");

            assertTrue(model.calls.isEmpty());
            assertEquals(Hold.BLOCKED, loop.hold());
            LoopEvent.HoldChanged blocked = eventsOf(LoopEvent.HoldChanged.class).get(0);
            assertEquals(Hold.BLOCKED, blocked.hold());
            assertEquals("没绑模型", blocked.reason(), "原因随事件交出去");
            assertEquals(1, inbox.count(EventTypes.QUERY), "没发出去的话还在队里");
            assertTrue(transcript.snapshot().isEmpty(), "端点检查在注入之前");

            events.clear();
            worldEvent("天黑了", true);
            for (int i = 0; i < 100; i++) {
                loop.tick();
            }
            assertTrue(model.calls.isEmpty(), "急件解不开配置问题");
            assertTrue(events.isEmpty());

            model.unavailable = null;
            loop.bindingChanged();

            assertEquals(1, model.calls.size(), "绑定变更解开");
            assertTrue(model.last().lastUser().contains("<query>你好</query>"));
        }

        @Test
        void ownerSpeakingAgainRetriesTheEndpointAndSaysWhyAgain() {
            model.unavailable = "没填 key";
            ownerSays("你好");
            ownerSays("在吗");

            assertEquals(2, eventsOf(LoopEvent.HoldChanged.class).stream()
                    .filter(e -> e.hold() == Hold.BLOCKED).count(), "每次主人开口都重新检查、重新告诉他");
            assertTrue(model.calls.isEmpty());
        }

        @Test
        void failedIsReleasedByUrgentInputButNotByAmbientInput() {
            ownerSays("挖矿");
            model.last().fail("网络断了");
            model.last().fail("网络断了");
            assertEquals(Hold.FAILED, loop.hold());

            worldEvent("刮风了", false);
            worldEvent("下雨了", false);
            worldEvent("打雷了", false);
            assertEquals(2, model.calls.size(), "非急件不解开失败");

            worldEvent("任务结束了", true);

            assertNull(loop.hold());
            assertEquals(3, model.calls.size(), "急件解开失败");
        }
    }

    // ---- 控制条目 ----

    @Nested
    class ControlEntries {

        @Test
        void clearRunsImmediatelyAfterStop() {
            ownerSays("去挖矿");
            loop.halt(HaltReason.OWNER_STOP);

            control(EventTypes.CLEAR);

            assertEquals(1, memory.clears, "按了停止再清空,当场就清");
            assertTrue(eventsOf(LoopEvent.TranscriptBoundary.class).stream()
                    .anyMatch(e -> e.kind() == LoopEvent.Boundary.CLEAR));
            assertEquals(Hold.OWNER_STOP, loop.hold(), "清空不解开停止");
        }

        @Test
        void controlWaitsWhileDead() {
            loop.halt(HaltReason.DEATH, "摔死");
            control(EventTypes.CLEAR);
            loop.tick();
            assertEquals(0, memory.clears, "死着不执行");

            loop.respawned();
            assertEquals(1, memory.clears);
        }

        @Test
        void controlWaitsWhileExternallyDriven() {
            host.external = true;
            control(EventTypes.COMPACT);
            loop.tick();
            assertTrue(model.calls.isEmpty(), "外接驾驶时不执行");

            host.external = false;
            loop.tick();
            assertEquals(List.of(false), memory.compactions, "交还后执行手动整理");
        }

        @Test
        void manualCompactionHoldsOffRunsUntilItLands() {
            control(EventTypes.COMPACT);
            Call compaction = model.last();
            assertEquals(Phase.COMPACT, loop.status().phase());
            compaction.stream("摘要写到一半");
            assertTrue(loop.status().compactProgress() > 0.0, "进度随摘要字数往上走");

            ownerSays("好了没");
            assertEquals(1, model.calls.size(), "整理期间不开 run");

            compaction.say("<summary>之前挖了矿</summary>");

            assertEquals(1, memory.applied);
            assertEquals(2, model.calls.size(), "落地后排着的话开 run");
            assertEquals(1, eventsOf(LoopEvent.RunStarted.class).size(), "手动整理本身不算一次 run");
        }

        @Test
        void wordsBehindAControlEntryWaitForIt() {
            ownerSays("去挖矿");
            Call first = model.last();
            control(EventTypes.CLEAR);
            ownerSays("清完再说这句");

            first.say("好的");

            assertEquals(1, memory.clears, "run 结束后先执行清空");
            assertEquals(2, model.calls.size());
            List<ConvoState.Msg> sent = model.last().request().messages();
            assertEquals(1, sent.size(), "排在清空后面的话进全新的上下文");
            assertTrue(model.last().lastUser().contains("清完再说这句"));
        }
    }

    // ---- run 里的插话与接续 ----

    @Nested
    class SteeringAndFollowUp {

        @Test
        void ownerWordsDuringToolsWaitForTheBatchThenMergeIntoOneMessage() {
            ownerSays("去挖矿");
            model.last().callTools(tool("c1"), tool("c2"));

            ownerSays("第一句");
            ownerSays("第二句");
            ownerSays("第三句");
            assertEquals(1, model.calls.size());
            assertTrue(tools.cancels.isEmpty(), "插话不打断工具");

            tools.finish("c1");
            assertEquals(1, model.calls.size(), "这批还没结算完");
            tools.finish("c2");

            assertEquals(2, model.calls.size(), "结算后调下一次模型");
            List<ConvoState.Msg> sent = model.last().request().messages();
            assertInstanceOf(ConvoState.Msg.Tool.class, sent.get(sent.size() - 2));
            String merged = ((ConvoState.Msg.User) sent.get(sent.size() - 1)).content();
            assertTrue(merged.indexOf("第一句") < merged.indexOf("第二句")
                    && merged.indexOf("第二句") < merged.indexOf("第三句"), "三句合成一条,顺序不变");
            assertTrue(eventsOf(LoopEvent.TurnStarted.class).get(1).ownerSpoke());
        }

        @Test
        void aContinuationTurnDoesNotCountAsTheOwnerSpeaking() {
            ownerSays("去挖矿");
            model.last().callTools(tool("c1"));
            tools.finish("c1");

            assertFalse(eventsOf(LoopEvent.TurnStarted.class).get(1).ownerSpoke(), "她接自己的话:句界衔接");
        }

        @Test
        void goalFollowUpJoinsOnlyWhenTheRunWouldEnd() {
            ownerSays("挖 64 个铁");
            model.last().callTools(tool("c1"));
            goalContinues("还差 30 个");
            tools.finish("c1");

            List<ConvoState.Msg> atBoundary = model.last().request().messages();
            assertInstanceOf(ConvoState.Msg.Tool.class, atBoundary.get(atBoundary.size() - 1),
                    "工具结算的边界不接续跑:工具结果后面直接调模型");
            assertEquals(1, inbox.count(EventTypes.GOAL), "续跑还排着");

            model.last().say("先挖着");

            assertEquals(3, model.calls.size(), "本来要停时接上续跑");
            assertTrue(model.last().lastUser().contains("还差 30 个"));
            assertFalse(eventsOf(LoopEvent.TurnStarted.class).get(2).ownerSpoke(), "续跑不算主人开口");
            assertTrue(eventsOf(LoopEvent.RunEnded.class).isEmpty(), "同一次 run 里接着走");
        }

        @Test
        void aRunThatEndsReportsDone() {
            ownerSays("你好");
            model.last().say("你好呀");

            LoopEvent.RunEnded ended = eventsOf(LoopEvent.RunEnded.class).get(0);
            assertEquals(RunEnd.DONE, ended.end());
            assertNull(loop.status().phase());
        }

        @Test
        void stopClearsAQueuedGoalWhileBusy() {
            ownerSays("挖 64 个铁");
            model.last().callTools(tool("c1"));
            goalContinues("还差 30 个");
            worldEvent("挨打了", false);

            loop.halt(HaltReason.OWNER_STOP);

            assertEquals(0, inbox.count(EventTypes.GOAL), "排着的续跑是被取代的指令");
            assertEquals(1, inbox.count(EventTypes.TASK_FINISHED), "事实留着");
            assertTrue(eventsOf(LoopEvent.Halted.class).get(0).reason().endsGoal());
        }

        @Test
        void stopClearsAQueuedGoalWhileIdle() {
            host.external = true;   // 让续跑排着不被取走
            goalContinues("还差 30 个");

            loop.halt(HaltReason.OWNER_STOP);

            assertEquals(0, inbox.count(EventTypes.GOAL), "闲时按停止同样清");
        }
    }

    // ---- 切断 ----

    @Nested
    class Halting {

        @Test
        void stopMidStreamDropsLateCallbacksAndLeavesAValidNextRequest() {
            ownerSays("讲个故事");
            Call cut = model.last();
            cut.stream("从前有");
            assertEquals(1, eventsOf(LoopEvent.ModelDelta.class).size());

            loop.halt(HaltReason.OWNER_STOP);
            assertTrue(cut.cancel().isCancelled(), "在飞的调用被取消");
            int before = events.size();

            cut.stream("座山");
            cut.callTools(tool("late"));

            assertEquals(before, events.size(), "取消之后的回调不再进内核");
            assertTrue(tools.batch.isEmpty(), "迟到的回复不派工具");
            assertInstanceOf(ConvoState.Msg.Halt.class, lastMessage());
            assertEquals("被主人打断", ((ConvoState.Msg.Halt) lastMessage()).reason());
            assertInstanceOf(RunEnd.Halted.class, eventsOf(LoopEvent.RunEnded.class).get(0).end());

            ownerSays("接着讲");
            List<ConvoState.Msg> wire = ProtocolView.forWire(model.last().request().messages());
            assertTrue(wire.stream().noneMatch(m -> m instanceof ConvoState.Msg.Halt));
            for (int i = 1; i < wire.size(); i++) {
                assertFalse(wire.get(i - 1) instanceof ConvoState.Msg.User && wire.get(i) instanceof ConvoState.Msg.User,
                        "没有相邻的 user");
            }
            String last = ((ConvoState.Msg.User) wire.get(wire.size() - 1)).content();
            assertTrue(last.contains("上一轮被打断：被主人打断") && last.contains("接着讲"));
        }

        @Test
        void stopDuringToolsAbandonsOnlyThisBatchAndStopsTheBody() {
            ownerSays("去挖矿");
            model.last().callTools(tool("c1"), tool("c2"));
            ToolPort.Sink old = tools.sink;
            tools.finish("c1");

            loop.halt(HaltReason.OWNER_STOP);

            assertEquals(List.of(true), tools.cancels, "身体收到叫停");
            assertTrue(tools.parked.contains(FakeTools.EXTERNAL_CALL), "外接模型挂着的调用不受内脑打断影响");
            tools.finish(old, "c2", "{\"success\":true}");
            assertTrue(transcript.snapshot().stream().noneMatch(m -> m instanceof ConvoState.Msg.Tool t
                    && t.toolCallId().equals("c2")), "迟到的结果不进历史");

            ownerSays("算了");
            List<ConvoState.Msg> wire = ProtocolView.forWire(model.last().request().messages());
            ConvoState.Msg.Tool c2 = wire.stream().filter(m -> m instanceof ConvoState.Msg.Tool t
                    && t.toolCallId().equals("c2")).map(ConvoState.Msg.Tool.class::cast).findFirst().orElseThrow();
            assertTrue(c2.content().contains("被主人打断"), "没结算的调用拿到带原因的结果");
        }

        @Test
        void stopWhileIdleStillStopsTheBody() {
            host.bodyTask = true;
            assertTrue(loop.status().busy());

            loop.halt(HaltReason.OWNER_STOP);

            assertEquals(List.of(true), tools.cancels, "闲着但身体有活:照样叫停");
            assertEquals(HaltReason.OWNER_STOP, eventsOf(LoopEvent.Halted.class).get(0).reason());
        }

        @Test
        void deathFreezesAndRespawnWakesWithEverythingQueued() {
            ownerSays("去挖矿");
            model.last().callTools(tool("c1"));

            loop.halt(HaltReason.DEATH, "掉进岩浆");

            assertEquals(List.of(false), tools.cancels, "死了不再叫停身体");
            assertEquals("你死了(掉进岩浆)", ((ConvoState.Msg.Halt) lastMessage()).reason());
            assertEquals(Hold.DEAD, loop.hold());

            ownerSays("你怎么了");
            worldEvent("你刚才死了", true);
            assertEquals(1, model.calls.size(), "死着一轮不开");
            assertEquals(2, inbox.size(), "死着照收");

            loop.respawned();

            assertNull(loop.hold());
            assertEquals(2, model.calls.size());
            String injected = model.last().lastUser();
            assertTrue(injected.contains("你刚才死了") && injected.contains("你怎么了"));
        }

        @Test
        void disconnectKeepsGoalQueueAndLeavesNoHold() {
            ownerSays("挖 64 个铁");
            model.last().callTools(tool("c1"));
            goalContinues("还差 30 个");
            worldEvent("刮风了", false);

            loop.halt(HaltReason.DISCONNECT);

            assertEquals(List.of(false), tools.cancels, "身体还在服务器里跑,不叫停");
            assertNull(loop.hold(), "登出不置停牌");
            assertEquals(1, inbox.count(EventTypes.GOAL), "不清队列");
            assertEquals(1, inbox.count(EventTypes.TASK_FINISHED));
            assertFalse(eventsOf(LoopEvent.Halted.class).get(0).reason().endsGoal(), "目标保留");
            assertEquals("主人断线了", ((ConvoState.Msg.Halt) lastMessage()).reason());

            worldEvent("task_finished", true);   // 离线补发的收尾

            assertEquals(2, model.calls.size(), "补发回来叫得醒她");
        }

        @Test
        void externalTakeoverVoidsTheInFlightTurn() {
            ownerSays("去挖矿");
            Call inFlight = model.last();

            host.external = true;
            loop.halt(HaltReason.EXTERNAL);

            assertTrue(inFlight.cancel().isCancelled());
            inFlight.callTools(tool("c1"));
            assertTrue(tools.batch.isEmpty(), "接管后在飞的回复不派工具");
            assertEquals(Hold.EXTERNAL, loop.hold());

            ownerSays("听得到吗");
            assertEquals(1, model.calls.size(), "外接驾驶时内脑不开 run");

            host.external = false;
            events.clear();
            loop.tick();
            assertEquals(List.of(new LoopEvent.HoldChanged(null, null)), eventsOf(LoopEvent.HoldChanged.class),
                    "交还报一次停牌变化");
            assertEquals(2, model.calls.size(), "外接期间攒的话按熟度处理");
        }

        @Test
        void externalTakeoverVoidsAnInFlightCompaction() {
            memory.due = true;
            ownerSays("去挖矿");
            Call compaction = model.last();
            assertEquals(Phase.COMPACT, loop.status().phase());

            host.external = true;
            loop.halt(HaltReason.EXTERNAL);
            compaction.say("<summary>摘要</summary>");

            assertEquals(0, memory.applied, "接管后在飞的压缩不换历史");
            assertFalse(transcript.snapshot().stream().anyMatch(m -> m instanceof ConvoState.Msg.Halt),
                    "整理被切断时对话没断,不记切断点");
        }

        @Test
        void disposeHaltsTheInFlightRunSoNothingWritesAfterIt() {
            ownerSays("去挖矿");
            Call inFlight = model.last();
            inFlight.callTools(tool("c1"));
            ToolPort.Sink old = tools.sink;
            worldEvent("刮风了", false);

            loop.halt(HaltReason.DISPOSE);
            int historySize = transcript.snapshot().size();

            tools.finish(old, "c1", "{\"success\":true}");
            inFlight.say("迟到的回复");

            assertEquals(historySize, transcript.snapshot().size(), "清表之后旧循环不再往历史里写");
            assertEquals(List.of(false), tools.cancels, "不叫停身体");
            assertEquals(1, inbox.count(EventTypes.TASK_FINISHED), "不清队列");
        }
    }

    // ---- 失败与重试 ----

    @Nested
    class Failures {

        @Test
        void aFailedCallIsRetriedOnceThenHolds() {
            ownerSays("挖矿");
            model.last().fail("网络断了");

            assertEquals(2, model.calls.size(), "重试一次");
            assertEquals(2, model.turnRequests, "重试重新组装请求,走的是同一个 startRun");
            assertInstanceOf(RunEnd.Failed.class, eventsOf(LoopEvent.RunEnded.class).get(0).end());
            assertEquals(2, eventsOf(LoopEvent.RunStarted.class).size());
            assertTrue(eventsOf(LoopEvent.TurnStarted.class).get(1).ownerSpoke(), "重试时主人的话同样还没回");
            assertTrue(transcript.snapshot().stream().noneMatch(m -> m instanceof ConvoState.Msg.Assistant),
                    "失败的回应不进历史");
            assertEquals("这一轮没连上(网络断了)", ((ConvoState.Msg.Halt) lastMessage()).reason());
            assertTrue(eventsOf(LoopEvent.TurnFailed.class).isEmpty(), "重试前不打扰主人");

            model.last().fail("网络断了");

            assertEquals(2, model.calls.size(), "一条链只重试一次");
            assertEquals(List.of(new LoopEvent.TurnFailed("网络断了")), eventsOf(LoopEvent.TurnFailed.class));
            assertEquals(Hold.FAILED, loop.hold());
        }

        @Test
        void theRetryPassesTheEndpointCheck() {
            ownerSays("挖矿");
            model.unavailable = "档案被删了";

            model.last().fail("网络断了");

            assertEquals(1, model.calls.size());
            assertEquals(Hold.BLOCKED, loop.hold(), "重试同样过端点检查");
        }

        @Test
        void theRetryPassesTheCompactionCheck() {
            ownerSays("挖矿");
            memory.due = true;

            model.last().fail("网络断了");

            assertEquals(List.of(true), memory.compactions, "重试同样先看自动压缩");
            model.last().say("<summary>摘要</summary>");
            assertEquals(3, model.calls.size(), "压完接着重试");
        }

        @Test
        void anAnsweredCallGivesTheChainAFreshRetry() {
            ownerSays("挖矿");
            model.last().fail("网络断了");
            model.last().callTools(tool("c1"));
            tools.finish("c1");
            model.last().fail("网络又断了");

            assertEquals(4, model.calls.size(), "回过话之后再失败,又有一次重试");
            assertNull(loop.hold());
        }

        @Test
        void anEmptyReplyFailsWithoutRetry() {
            ownerSays("挖矿");
            model.last().say("");

            assertEquals(1, model.calls.size());
            assertEquals(List.of(new LoopEvent.TurnFailed(AgentLoop.EMPTY_REPLY)), eventsOf(LoopEvent.TurnFailed.class));
            assertEquals(Hold.FAILED, loop.hold());
        }

        @Test
        void urgentInputQueuedDuringTheFailureMeansNoHold() {
            ownerSays("挖矿");
            model.last().fail("网络断了");
            ownerSays("还在吗");   // 重试在飞时主人又开口

            model.last().fail("网络断了");

            assertEquals(1, eventsOf(LoopEvent.TurnFailed.class).size());
            assertNull(loop.hold(), "排着急件时不停牌");
            assertEquals(3, model.calls.size(), "排着的话接着开 run");
            assertTrue(model.last().lastUser().contains("还在吗"));
        }
    }

    // ---- 压缩 ----

    @Nested
    class EndpointGate {

        @Test
        void blockedCarriesItsReasonUntilTheBindingIsFixed() {
            model.unavailable = "没绑模型";
            ownerSays("在吗");

            assertEquals(Hold.BLOCKED, loop.hold());
            assertEquals("没绑模型", loop.status().holdReason(), "界面从快照里读得到为什么不动");
            assertTrue(model.calls.isEmpty());

            model.unavailable = null;
            loop.bindingChanged();

            assertNull(loop.status().holdReason());
            assertEquals(1, model.calls.size(), "排着的那句话接着走");
        }

        @Test
        void aQueuedCompactionWaitsForTheEndpointInsteadOfFailing() {
            model.unavailable = "没绑模型";
            control(EventTypes.COMPACT);

            assertTrue(model.calls.isEmpty(), "整理要发请求,端点不可用就不发");
            assertEquals(Hold.BLOCKED, loop.hold());
            assertEquals(1, inbox.size(), "条目留在队首");
            int announced = eventsOf(LoopEvent.HoldChanged.class).size();
            loop.tick();
            loop.tick();
            assertEquals(announced, eventsOf(LoopEvent.HoldChanged.class).size(), "停牌期间每 tick 推进也不重复报");

            model.unavailable = null;
            loop.bindingChanged();

            assertEquals(1, model.calls.size(), "绑定改好了,按过的整理自己接着走");
            assertEquals(Phase.COMPACT, loop.status().phase());
        }

        @Test
        void clearingNeedsNoModelSoItHappensEvenWhenBlocked() {
            model.unavailable = "没绑模型";
            transcript.addUser("旧话");
            control(EventTypes.CLEAR);

            assertEquals(1, memory.clears);
            assertTrue(inbox.isEmpty());
        }
    }

    @Nested
    class Consult {

        @Test
        void aSideCallReportsItsUsageButStartsNoRun() {
            List<ModelOutcome> outcomes = new ArrayList<>();
            loop.consult(LoopEvent.Purpose.GOAL, new ModelRequest(List.of(), List.of(), "judge"),
                    new CancelToken(), outcomes::add);

            assertNull(loop.status().phase(), "旁路调用不占内核");
            assertTrue(eventsOf(LoopEvent.RunStarted.class).isEmpty());
            Usage used = new Usage(10, 2, 0, 0);
            model.last().onDone().accept(new ModelOutcome.Answered(new AssistantTurn("met", List.of(), null), used));

            List<LoopEvent.ModelUsed> usage = eventsOf(LoopEvent.ModelUsed.class);
            assertEquals(1, usage.size(), "用量照样进账");
            assertEquals(LoopEvent.Purpose.GOAL, usage.get(0).purpose());
            assertEquals(used, usage.get(0).usage());
            assertInstanceOf(ModelOutcome.Answered.class, outcomes.get(0));
            assertTrue(transcript.snapshot().isEmpty(), "不进历史");
        }

        @Test
        void anUnusableEndpointFailsWithoutCallingTheModel() {
            model.unavailable = "没绑模型";
            List<ModelOutcome> outcomes = new ArrayList<>();
            loop.consult(LoopEvent.Purpose.GOAL, new ModelRequest(List.of(), List.of(), "judge"),
                    new CancelToken(), outcomes::add);

            assertTrue(model.calls.isEmpty());
            assertEquals("没绑模型", assertInstanceOf(ModelOutcome.Failed.class, outcomes.get(0)).words());
            assertNull(loop.hold(), "旁路调用不进停牌");
        }
    }

    @Nested
    class Compaction {

        @Test
        void autoCompactionHappensBeforeInjectionAndKeepsTheOwnersWordsVerbatim() {
            transcript.addUser("<query>很久以前的话</query>");
            transcript.addAssistant(new AssistantTurn("很久以前的回答", List.of(), null));
            memory.due = true;

            ownerSays("挖三个铁,放进左边的箱子");

            assertTrue(memory.historyAtSplit.stream().noneMatch(m -> m instanceof ConvoState.Msg.User u
                    && u.content().contains("挖三个铁")), "切分时主人刚说的话还没进历史");
            assertEquals(1, model.calls.size(), "先发的是压缩");

            model.last().say("之前聊过天");

            assertEquals(2, model.calls.size());
            List<ConvoState.Msg> sent = model.last().request().messages();
            assertEquals("[摘要] 之前聊过天", ((ConvoState.Msg.User) sent.get(0)).content());
            assertTrue(model.last().lastUser().contains("<query>挖三个铁,放进左边的箱子</query>"), "逐字保留");
            assertEquals(List.of(LoopEvent.Boundary.COMPACT), eventsOf(LoopEvent.TranscriptBoundary.class).stream()
                    .map(LoopEvent.TranscriptBoundary::kind).toList());
        }

        @Test
        void aFailedAutoCompactionLetsTheTurnGoOnUncompacted() {
            memory.due = true;
            ownerSays("挖矿");

            model.last().fail("网络断了");

            assertEquals(List.of("网络断了"), memory.failures);
            assertEquals(2, model.calls.size(), "历史不动,照原样调模型");
            assertTrue(model.last().lastUser().contains("挖矿"));
        }

        @Test
        void usageIsReportedForTurnsAndCompactions() {
            memory.due = true;
            ownerSays("挖矿");
            model.last().say("<summary>摘要</summary>");
            model.last().say("好");

            assertEquals(List.of(LoopEvent.Purpose.COMPACT, LoopEvent.Purpose.TURN),
                    eventsOf(LoopEvent.ModelUsed.class).stream().map(LoopEvent.ModelUsed::purpose).toList());
        }
    }

    @Test
    void statusReadsFromOnePlace() {
        LoopStatus idle = loop.status();
        assertFalse(idle.busy());
        assertFalse(idle.canInterrupt());

        host.external = true;
        ownerSays("你好");
        LoopStatus queued = loop.status();
        assertEquals(Hold.EXTERNAL, queued.hold());
        assertEquals(List.of("<query>你好</query>"), queued.queuedPreview());
        assertTrue(queued.canInterrupt(), "排着主人的话:停止键可按");
        assertFalse(queued.busy());

        host.bodyTask = true;
        assertTrue(loop.status().busy());
        assertEquals("挖 64 块泥土", loop.status().activity());
    }
}
