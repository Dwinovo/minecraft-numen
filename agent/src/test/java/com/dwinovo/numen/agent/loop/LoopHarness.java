package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.provider.Usage;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 驱动一个真的循环内核的测试底座。端口全是假的、同步的:模型调用停在 {@link FakeModel#calls} 里等测试替它回话,
 * 工具停在 {@link FakeTools} 里等测试替它结算。测试就是按真实的先后顺序把这些回调喂回去。
 *
 * <p>内核自己的测试和挂在内核上的组件(长期目标)的测试共用这一份,假端口只有一处。
 */
public abstract class LoopHarness {

    protected static final long T0 = 1_000_000L;

    protected final ConvoState transcript = new ConvoState();
    protected final EventQueue inbox = new EventQueue(EventQueue.Journal.NONE);
    protected final FakeModel model = new FakeModel();
    protected final FakeTools tools = new FakeTools();
    protected final FakeMemory memory = new FakeMemory();
    protected final FakeHost host = new FakeHost();
    protected final List<LoopEvent> events = new ArrayList<>();
    protected AgentLoop loop;

    @BeforeEach
    protected void setUpLoop() {
        loop = new AgentLoop("test", model, tools, transcript, inbox, memory, host);
        loop.subscribe(events::add);
    }

    // ---- 假端口 ----

    /** 一次停着等回话的模型调用。 */
    public record Call(ModelRequest request, CancelToken cancel, Consumer<ModelPort.Delta> onDelta,
                       Consumer<ModelOutcome> onDone) {

        public void say(String text) {
            onDone.accept(new ModelOutcome.Answered(new AssistantTurn(text, List.of(), null), Usage.ZERO));
        }

        public void callTools(LlmToolCall... calls) {
            onDone.accept(new ModelOutcome.Answered(new AssistantTurn("", List.of(calls), null), Usage.ZERO));
        }

        public void fail(String words) {
            onDone.accept(new ModelOutcome.Failed(words));
        }

        public void stream(String content) {
            onDelta.accept(new ModelPort.Delta(content, ""));
        }

        /** 这次请求里最后一条 user 消息的原文。 */
        public String lastUser() {
            List<ConvoState.Msg> messages = request.messages();
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof ConvoState.Msg.User u) {
                    return u.content();
                }
            }
            return null;
        }
    }

    public final class FakeModel implements ModelPort {
        public String unavailable;
        public int turnRequests;
        public final List<Call> calls = new ArrayList<>();

        @Override
        public String unavailable() {
            return unavailable;
        }

        @Override
        public ModelRequest turnRequest() {
            turnRequests++;
            return new ModelRequest(transcript.snapshot(), List.of(), "system");
        }

        @Override
        public void call(ModelRequest request, CancelToken cancel, Consumer<Delta> onDelta,
                         Consumer<ModelOutcome> onDone) {
            calls.add(new Call(request, cancel, onDelta, onDone));
        }

        public Call last() {
            return calls.get(calls.size() - 1);
        }
    }

    /**
     * 工具口。{@link #parked} 模拟传输层的在飞表:这批调用之外,还挂着一个外接模型经 actuator 派的调用,
     * 内脑的打断只许按自己这批的 id 收拾。
     */
    public final class FakeTools implements ToolPort {
        public static final String EXTERNAL_CALL = "ext-1";
        public final Set<String> parked = new LinkedHashSet<>(Set.of(EXTERNAL_CALL));
        public final List<LlmToolCall> batch = new ArrayList<>();
        public final List<Boolean> cancels = new ArrayList<>();
        public Sink sink;

        @Override
        public void run(List<LlmToolCall> calls, Sink sink) {
            this.sink = sink;
            batch.addAll(calls);
            for (LlmToolCall call : calls) {
                parked.add(call.id());
                sink.started(call);
            }
        }

        @Override
        public List<String> cancel(boolean stopBody) {
            cancels.add(stopBody);
            List<String> ids = batch.stream().map(LlmToolCall::id).toList();
            ids.forEach(parked::remove);
            batch.clear();
            return ids;
        }

        /** 结算一个调用;这批全部结算就报 settled。{@code via} 让测试模拟切断之后才迟到的结果。 */
        public void finish(Sink via, String id, String json) {
            LlmToolCall call = new LlmToolCall(id, "mine", "{}");
            batch.removeIf(c -> c.id().equals(id));
            parked.remove(id);
            via.finished(call, json);
            if (batch.isEmpty()) {
                via.settled();
            }
        }

        public void finish(String id) {
            finish(sink, id, "{\"success\":true}");
        }
    }

    public final class FakeMemory implements MemoryPort {
        public boolean due;
        public int clears;
        public int applied;
        public final List<Boolean> compactions = new ArrayList<>();
        public final List<String> failures = new ArrayList<>();
        /** 切分那一刻的历史——自动压缩在注入之前,主人刚说的话不该在这里面。 */
        public List<ConvoState.Msg> historyAtSplit;

        @Override
        public boolean compactionDue() {
            return due;
        }

        @Override
        public Compaction compaction(boolean auto) {
            compactions.add(auto);
            historyAtSplit = transcript.snapshot();
            return new Compaction() {
                @Override
                public ModelRequest request() {
                    return new ModelRequest(List.of(new ConvoState.Msg.User("请总结")), List.of(), "compact");
                }

                @Override
                public boolean apply(AssistantTurn reply, Usage usage) {
                    if (reply.content().isBlank()) {
                        return false;
                    }
                    applied++;
                    due = false;
                    transcript.replaceAll(List.of(new ConvoState.Msg.User("[摘要] " + reply.content())));
                    return true;
                }

                @Override
                public void failed(String why) {
                    failures.add(why);
                    due = false;
                }
            };
        }

        @Override
        public void clear() {
            clears++;
            transcript.replaceAll(List.of());
        }
    }

    public static final class FakeHost implements HostPort {
        public long now = T0;
        public int level = 3;
        public boolean external;
        public String preamble = "";
        public boolean bodyTask;

        @Override
        public long now() {
            return now;
        }

        @Override
        public int initiativeLevel() {
            return level;
        }

        @Override
        public boolean externallyDriven() {
            return external;
        }

        @Override
        public String injectionPreamble() {
            return preamble;
        }

        @Override
        public boolean bodyTaskRunning() {
            return bodyTask;
        }

        @Override
        public String activity() {
            return bodyTask ? "挖 64 块泥土" : null;
        }
    }

    // ---- 输入与读事件的小工具 ----

    protected void ownerSays(String words) {
        loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY, "<query>" + words + "</query>", 0, false)));
    }

    protected void worldEvent(String text, boolean urgent) {
        loop.push(List.of(new EventQueue.Entry(EventTypes.TASK_FINISHED, "<event>" + text + "</event>", 0, urgent)));
    }

    protected void goalContinues(String text) {
        loop.push(List.of(new EventQueue.Entry(EventTypes.GOAL, "<goal-progress>" + text + "</goal-progress>", 0, false)));
    }

    protected void control(String type) {
        loop.push(List.of(new EventQueue.Entry(type, type, 0, false)));
    }

    protected <E extends LoopEvent> List<E> eventsOf(Class<E> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    protected static LlmToolCall tool(String id) {
        return new LlmToolCall(id, "mine", "{}");
    }

    protected ConvoState.Msg lastMessage() {
        return transcript.lastMessage();
    }

}
