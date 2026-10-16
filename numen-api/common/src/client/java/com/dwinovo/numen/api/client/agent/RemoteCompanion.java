package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.goal.GoalState;
import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.brain.CompanionState;
import com.dwinovo.numen.api.brain.Feed;
import com.dwinovo.numen.api.brain.OwnerInput;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 服务端大脑的一只同伴在主人客户端上的样子:一个只读视图加一副发话的手。读的东西全是服务端推来的({@link Feed}),
 * 照序放进来、不判断不推断;做的事——说话、停止、整理、设目标、换人设——不在这里办,只变成 {@link OwnerInput} 发给服务端,
 * 由它判"这只是不是你的、现在能不能"。这里不存任何她的东西:关了游戏什么都不剩。
 *
 * <p>事件放进模型之后原样转给 {@code effects}(气泡、聊天栏、语音、提示条这些画的和听的),模型和它们之间没有别的口。
 *
 * <p>线程:客户端主线程。
 */
public final class RemoteCompanion implements CompanionView {

    private final UUID uuid;
    private final Consumer<OwnerInput> send;
    private final Consumer<Feed> effects;
    private final Consumer<ConvoLog.Line> lineSink;

    private CompanionState state = CompanionState.idle();
    private final StringBuilder partial = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    /** 她自己那本日志里推来的行(实时的),从老到新;翻页取回的老行在 {@link RemoteTranscripts}。 */
    private final List<ConvoLog.Line> lines = new ArrayList<>();

    /**
     * @param send     发给服务端
     * @param effects  画的和听的:每个事件在模型更新之后交给它
     * @param lineSink 推来的日志行再交给会话记录({@link RemoteTranscripts})
     */
    public RemoteCompanion(UUID uuid, Consumer<OwnerInput> send, Consumer<Feed> effects,
                           Consumer<ConvoLog.Line> lineSink) {
        this.uuid = uuid;
        this.send = send;
        this.effects = effects;
        this.lineSink = lineSink;
    }

    /** 服务端推来一包:照序放进模型,再交给画的和听的。 */
    public void apply(List<Feed> events) {
        for (Feed event : events) {
            switch (event) {
                case Feed.TurnStart start -> clearPartial();
                case Feed.Delta delta -> {
                    partial.append(delta.content());
                    reasoning.append(delta.reasoning());
                }
                case Feed.Replied replied -> clearPartial();
                case Feed.TurnCut cut -> clearPartial();
                case Feed.Line line -> {
                    lines.add(line.line());
                    lineSink.accept(line.line());
                }
                case Feed.Status status -> state = status.state();
                case Feed.Halted halted -> { }
                case Feed.Failed failed -> { }
                case Feed.Blocked blocked -> { }
                case Feed.Pending pending -> { }
            }
            effects.accept(event);
        }
    }

    private void clearPartial() {
        partial.setLength(0);
        reasoning.setLength(0);
    }

    /** 服务端对这只同伴最近一次说的样子。 */
    public CompanionState state() {
        return state;
    }

    // ---- 读 ----

    @Override
    public UUID entityUuid() {
        return uuid;
    }

    @Override
    public LoopStatus status() {
        return new LoopStatus(state.phase(), state.hold(), state.holdReason(), state.bodyTaskRunning(), state.queued(),
                state.compactProgress(), state.activity());
    }

    @Override
    public String livePartial() {
        return partial.toString();
    }

    @Override
    public String liveReasoning() {
        return reasoning.toString();
    }

    @Override
    public long totalTokensUsed() {
        return state.totalTokens();
    }

    @Override
    public Usage usageTotals() {
        return state.usageSum();
    }

    @Override
    public Usage lastUsage() {
        return state.usageLast();
    }

    @Override
    public long wastedTokens() {
        return state.wastedTokens();
    }

    @Override
    public int wasteCount() {
        return state.wasteCount();
    }

    @Override
    public boolean isToolCallOutstanding(String callId) {
        return state.outstanding().contains(callId);
    }

    @Override
    public GoalState goal() {
        return state.goal() == null ? null : GoalState.fromJson(state.goal());
    }

    @Override
    public String compactProblem() {
        return state.compactProblem();
    }

    @Override
    public String clearProblem() {
        return state.clearProblem();
    }

    @Override
    public int contextPercent() {
        return state.contextPercent();
    }

    @Override
    public int modelWindow() {
        return state.window();
    }

    @Override
    public String personaId() {
        return state.personaId();
    }

    /** 人设的 id 就是它的名字(文件名),所以名字不用另问。 */
    @Override
    public String personaName() {
        return state.personaId();
    }

    @Override
    public String providerEntryId() {
        return state.providerId();
    }

    @Override
    public String conversation() {
        return state.conversation();
    }

    @Override
    public int messageCount() {
        return state.messages();
    }

    @Override
    public List<ConvoLog.Line> display() {
        return java.util.Collections.unmodifiableList(lines);
    }

    // ---- 做 ----

    /** 话发出去了;服务端那头压没压着,从推来的状态里看,这里只报"没压着"。 */
    @Override
    public boolean submitPrompt(String text) {
        send.accept(new OwnerInput.Say(uuid.toString(), text));
        return true;
    }

    @Override
    public void submitSkill(String echo, String skill, String args) {
        send.accept(new OwnerInput.Command(uuid, echo));
    }

    /** 能不能整理的判据在服务端、随状态推来;不能就当场回理由,能就发出去。 */
    @Override
    public String requestCompact() {
        if (state.compactProblem() != null) {
            return state.compactProblem();
        }
        send.accept(new OwnerInput.Compact(uuid));
        return null;
    }

    @Override
    public String requestClearContext() {
        if (state.clearProblem() != null) {
            return state.clearProblem();
        }
        send.accept(new OwnerInput.Clear(uuid));
        return null;
    }

    @Override
    public void abort() {
        send.accept(new OwnerInput.Stop(uuid.toString()));
    }

    @Override
    public void setGoal(GoalState next, String echo) {
        send.accept(new OwnerInput.SetGoal(uuid, next.objective(), echo));
    }

    @Override
    public void clearGoal(String why) {
        send.accept(new OwnerInput.ClearGoal(uuid));
    }

    @Override
    public void setPersona(String id) {
        send.accept(new OwnerInput.SetPersona(uuid, id));
    }

    @Override
    public void setProviderEntry(String entryId) {
        send.accept(new OwnerInput.SetProvider(uuid, entryId));
    }
}
