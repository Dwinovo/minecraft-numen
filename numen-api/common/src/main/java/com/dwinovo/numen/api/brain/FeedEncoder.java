package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.loop.Hold;
import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.agent.loop.Phase;
import com.dwinovo.numen.api.agent.loop.RunEnd;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 把一只同伴的循环里发生的事译成给主人客户端的 {@link Feed},按刻攒成一包({@link #flush}):流式增量合并成一条,
 * 排着的输入与她此刻的样子变了才发。译的地方只有这一处,服务端大脑和客户端的视图之间没有别的口。
 *
 * <p>也记着在飞回复已长出的半截({@link #snapshot}),客户端中途进服或重连时一次补齐,不用等下一个字。
 *
 * <p>线程:服务端主线程。
 */
public final class FeedEncoder {

    private final CompanionLoop loop;
    private final Consumer<List<Feed>> out;
    private final List<Feed> events = new ArrayList<>();
    /** 在飞回复已长出的正文与思考;回复落库、失败或被切断时清空。 */
    private final StringBuilder partial = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private CompanionState sentState;
    private List<EventQueue.Preview> sentPending;

    /** @param out 一刻攒下的事件;没有事件不会调 */
    public FeedEncoder(CompanionLoop loop, Consumer<List<Feed>> out) {
        this.loop = loop;
        this.out = out;
        loop.subscribe(this::on);
        loop.log().onDisplay(line -> events.add(new Feed.Line(line)));
    }

    private void on(LoopEvent event) {
        switch (event) {
            case LoopEvent.TurnStarted turn -> {
                clearPartial();
                events.add(new Feed.TurnStart(turn.ownerSpoke()));
            }
            case LoopEvent.ModelDelta delta -> {
                partial.append(delta.content());
                reasoning.append(delta.reasoning());
                if (!events.isEmpty() && events.get(events.size() - 1) instanceof Feed.Delta last) {
                    events.set(events.size() - 1,
                            new Feed.Delta(last.content() + delta.content(), last.reasoning() + delta.reasoning()));
                } else {
                    events.add(new Feed.Delta(delta.content(), delta.reasoning()));
                }
            }
            case LoopEvent.AssistantMessage message -> {
                clearPartial();
                events.add(new Feed.Replied(message.turn().content(), message.turn().hasToolCalls()));
            }
            case LoopEvent.RunEnded ended -> {
                if (!(ended.end() instanceof RunEnd.Done)) {
                    clearPartial();   // 失败或被切断:半截文字作废、语音收尾
                    events.add(new Feed.TurnCut());
                }
            }
            case LoopEvent.TurnFailed failed -> events.add(new Feed.Failed(failed.cause()));
            case LoopEvent.HoldChanged changed -> {
                if (changed.hold() == Hold.BLOCKED) {
                    events.add(new Feed.Blocked(changed.cause()));
                }
            }
            case LoopEvent.Halted halted -> events.add(new Feed.Halted());
            case LoopEvent.RunStarted ignored -> { }
            case LoopEvent.ToolStarted ignored -> { }
            case LoopEvent.ToolFinished ignored -> { }
            case LoopEvent.ApiCalled ignored -> { }
            case LoopEvent.ProgramEnded ignored -> { }
            case LoopEvent.ModelUsed ignored -> { }
            case LoopEvent.TranscriptBoundary ignored -> { }
        }
    }

    /** 主人清空了她的记忆:告诉面板把她的记录作废,在飞的半截文字也不要了。 */
    public void wiped() {
        clearPartial();
        events.add(new Feed.Wiped());
    }

    private void clearPartial() {
        partial.setLength(0);
        reasoning.setLength(0);
    }

    /** 每个服务器刻一次:攒下的事件,加上变了的排队与状态,一包交出去。 */
    public void flush() {
        List<EventQueue.Preview> pending = loop.status().queuedPreview();
        if (!pending.equals(sentPending)) {
            sentPending = pending;
            events.add(new Feed.Pending(pending));
        }
        CompanionState state = loop.state();
        if (!state.equals(sentState)) {
            sentState = state;
            events.add(new Feed.Status(state));
        }
        if (events.isEmpty()) {
            return;
        }
        List<Feed> batch = List.copyOf(events);
        events.clear();
        out.accept(batch);
    }

    /**
     * 客户端刚连上要补的全部:她此刻的样子、排着的输入,以及在飞回复已长出的半截。日志里的行客户端自己按页来取。
     * 补了之后这一刻攒着的事件和变化照常发,不重复这些(状态与排队记作已发)。
     */
    public List<Feed> snapshot() {
        List<EventQueue.Preview> pending = loop.status().queuedPreview();
        CompanionState state = loop.state();
        sentPending = pending;
        sentState = state;
        List<Feed> all = new ArrayList<>();
        all.add(new Feed.Status(state));
        all.add(new Feed.Pending(pending));
        if (loop.status().phase() == Phase.MODEL) {
            all.add(new Feed.TurnStart(false));
            if (partial.length() > 0 || reasoning.length() > 0) {
                all.add(new Feed.Delta(partial.toString(), reasoning.toString()));
            }
        }
        return all;
    }
}
