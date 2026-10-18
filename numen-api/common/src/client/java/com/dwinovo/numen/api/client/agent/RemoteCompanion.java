package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.goal.GoalState;
import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.brain.CompanionState;
import com.dwinovo.numen.api.brain.Feed;
import com.dwinovo.numen.api.brain.OwnerInput;
import com.dwinovo.numen.api.brain.Slot;

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
public final class RemoteCompanion {

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
                case Feed.Wiped wiped -> {
                    lines.clear();
                    clearPartial();
                }
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

    public UUID entityUuid() {
        return uuid;
    }

    /** 内核此刻的只读快照:忙不忙、为什么不动、排着什么、在干什么。 */
    public LoopStatus status() {
        return new LoopStatus(state.phase(), state.hold(), state.holdReason(), state.bodyTaskRunning(), state.queued(),
                state.compactProgress(), state.activity());
    }

    /** 在飞的回复已长出的正文(空串 = 没有):对话面板打字机的数据源。 */
    public String livePartial() {
        return partial.toString();
    }

    /** 在飞回合的思考流(空串 = 没有或已落库)。 */
    public String liveReasoning() {
        return reasoning.toString();
    }

    /** 累计用了多少 token。 */
    public long totalTokensUsed() {
        return state.totalTokens();
    }

    /** 四元累计用量(跨会话)。 */
    public Usage usageTotals() {
        return state.usageSum();
    }

    /** 最近一轮的用量:命中率取自它,累计命中率会被历史稀释。 */
    public Usage lastUsage() {
        return state.usageLast();
    }

    /** 缓存重计费的诊断数:本该命中却重新处理的 token。 */
    public long wastedTokens() {
        return state.wastedTokens();
    }

    /** 缓存重计费发生的次数。 */
    public int wasteCount() {
        return state.wasteCount();
    }

    /** 这次工具调用的结果还会不会来;历史里没结果、这里又答 false 的调用是被切断的。 */
    public boolean isToolCallOutstanding(String callId) {
        return state.outstanding().contains(callId);
    }

    /** 当前的长期目标;没有是 {@code null}。 */
    public GoalState goal() {
        return state.goal() == null ? null : GoalState.fromJson(state.goal());
    }

    /** 此刻不能整理记忆的理由(语言键);{@code null} = 能。 */
    public String compactProblem() {
        return state.compactProblem();
    }

    /** 此刻不能清空上下文的理由(语言键);{@code null} = 能。 */
    public String clearProblem() {
        return state.clearProblem();
    }

    /** 上下文水位百分比。 */
    public int contextPercent() {
        return state.contextPercent();
    }

    /** 她绑定的模型的上下文窗口;没绑是 0。 */
    public int modelWindow() {
        return state.window();
    }

    /** 她绑定的人设 id;没绑是 {@code null}。 */
    public String personaId() {
        return state.personaId();
    }

    /** 人设的 id 就是它的名字(文件名),所以名字不用另问。 */
    public String personaName() {
        return state.personaId();
    }

    /** 她绑定的模型档案 id;没绑是 {@code null}。 */
    public String providerEntryId() {
        return state.providerId();
    }

    /** 她绑定的声线 id;没绑是 {@code null}。 */
    public String voiceId() {
        return state.voiceId();
    }

    /** 她绑定的皮肤 id;没绑是 {@code null}。 */
    public String skinId() {
        return state.skinId();
    }

    /** 她此刻在哪个会话里(最后一次是被哪个会话叫醒的);{@code null} = 就他俩。 */
    public String conversation() {
        return state.conversation();
    }

    /** 她此刻的上下文里有几条消息。 */
    public int messageCount() {
        return state.messages();
    }

    /** 她自己那本日志里已经实时推来的面板行,从老到新。 */
    public List<ConvoLog.Line> display() {
        return java.util.Collections.unmodifiableList(lines);
    }

    // ---- 做 ----

    /** 话发出去了;服务端那头压没压着,从推来的状态里看,这里只报"没压着"。 */
    public boolean submitPrompt(String text) {
        send.accept(new OwnerInput.Say(uuid.toString(), text));
        return true;
    }

    /** 一条技能命令:{@code echo} 是主人打的原文;技能正文由服务端展开。 */
    public void submitSkill(String echo, String skill, String args) {
        send.accept(new OwnerInput.Command(uuid, echo));
    }

    /** 能不能整理的判据在服务端、随状态推来;不能就当场回理由,能就发出去。 */
    public String requestCompact() {
        if (state.compactProblem() != null) {
            return state.compactProblem();
        }
        send.accept(new OwnerInput.Compact(uuid));
        return null;
    }

    /** 主人要求清空上下文,形状同 {@link #requestCompact}。 */
    public String requestClearContext() {
        if (state.clearProblem() != null) {
            return state.clearProblem();
        }
        send.accept(new OwnerInput.Clear(uuid));
        return null;
    }

    /** 导出她的记忆:服务端打成 zip 推回来,存成文件见 {@code MemoryFiles}(选好存哪儿之后才调)。 */
    public void requestExport() {
        send.accept(new OwnerInput.Export(uuid));
    }

    /** 清空她的记忆(界面在主人确认之后才调);服务端办完推 {@link Feed.Wiped}。 */
    public void requestWipe() {
        send.accept(new OwnerInput.Wipe(uuid));
    }

    /** 主人按停止。 */
    public void abort() {
        send.accept(new OwnerInput.Stop(uuid.toString()));
    }

    /** 定一个长期目标;{@code echo} 是主人打的原文,聊天里要有这行字。 */
    public void setGoal(GoalState next, String echo) {
        send.accept(new OwnerInput.SetGoal(uuid, next.objective(), echo));
    }

    /** 收工。 */
    public void clearGoal(String why) {
        send.accept(new OwnerInput.ClearGoal(uuid));
    }

    /** 换她的一项绑定(档案、人设、声线、皮肤);{@code null} = 不绑。条目在不在库里由服务端判。 */
    public void bind(Slot slot, String entryId) {
        send.accept(new OwnerInput.Bind(uuid, slot, entryId));
    }
}
