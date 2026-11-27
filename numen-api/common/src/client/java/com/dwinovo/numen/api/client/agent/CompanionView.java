package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.goal.GoalState;
import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.mind.CompanionState;
import com.dwinovo.numen.api.mind.Feed;
import com.dwinovo.numen.api.agent.skill.SkillInjection;
import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.owner.OwnerInput;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.mind.LoopHost;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.payload.OwnerInputPayload;
import com.dwinovo.numen.api.mind.Slot;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 一只同伴的大脑在界面这头的样子:一个只读视图加一副发话的手。读的东西全是她的大脑发来的({@link Feed}),
 * 照序放进来、不判断不推断;做的事——说话、停止、整理、设目标、换人设——直接交给她的大脑({@link LoopHost}),她的东西都在大脑那里;
 * 只有分享是服务端管的,变成 {@link OwnerInput} 发给服务端,由它判"是不是你的、现在能不能"。
 *
 * <p>事件放进模型之后原样转给 {@code effects}(气泡、聊天栏、语音、提示条这些画的和听的),模型和它们之间没有别的口。
 *
 * <p>线程:客户端主线程。
 */
public final class CompanionView {

    private final UUID uuid;
    private final LoopHost host;
    private final Consumer<Feed> effects;

    private CompanionState state = CompanionState.idle();
    private final StringBuilder partial = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();

    /**
     * @param host     她的大脑
     * @param effects  画的和听的:每个事件在模型更新之后交给它
     */
    public CompanionView(UUID uuid, LoopHost host, Consumer<Feed> effects) {
        this.uuid = uuid;
        this.host = host;
        this.effects = effects;
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

    /** 她的大脑最近一次说的她的样子。 */
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

    /** 她这一项绑着什么:哪一条、谁绑的(连同他的名字);没绑是 {@code null}。 */
    public CompanionState.Binding binding(Slot slot) {
        return state.binding(slot);
    }

    /**
     * 她这一项绑的是不是 {@code viewer} 库里的这一条(见 {@link CompanionState.Binding#is}):列表上"当前绑的是哪一行"按它认,
     * 别人绑的同名条目不算。
     */
    public boolean binds(Slot slot, UUID viewer, String entryId) {
        CompanionState.Binding binding = state.binding(slot);
        return binding != null && binding.is(viewer, entryId);
    }

    /** 她绑的人设叫什么(人设的 id 就是它的名字);没绑是 {@code null}。 */
    public String personaName() {
        CompanionState.Binding binding = state.binding(Slot.PERSONA);
        return binding == null ? null : binding.id();
    }

    /** 她穿着的皮肤(签名缓存里的引用,{@code SkinRef} 的文字写法);默认皮肤是 {@code null}。 */
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

    // ---- 做 ----

    /** 话交给她的大脑;大脑那头压没压着,从状态里看,这里只报"没压着"。 */
    public boolean submitPrompt(String text) {
        return host.submitPrompt(text);
    }

    /** 一条技能命令:{@code echo} 是主人打的原文;技能的正文向她的技能库(随发布的向服务端)取到了再交给大脑。 */
    public void submitSkill(String echo, String skill, String args) {
        host.skills().read(skill).whenComplete((info, failed) -> onClientThread(() -> {
            if (failed == null) {
                host.submitCommand(echo, SkillInjection.body(info, args));
            }
        }));
    }

    /** 能不能整理的判据在大脑、随状态给;不能就当场回理由,能就排上。{@code focus} 是摘要要特别留意的,没有传空串。 */
    public String requestCompact(String focus) {
        return host.requestCompact(focus.isBlank() ? null : focus.strip());
    }

    /** 主人要求清空上下文,形状同 {@link #requestCompact}。 */
    public String requestClearContext() {
        return host.requestClearContext();
    }

    /** 导出她的记忆:对话记录(向服务端要)与札记打成的 zip(选好存哪儿之后才调,存成文件见 {@code MemoryFiles})。 */
    public java.util.concurrent.CompletableFuture<byte[]> requestExport() {
        return host.exportMemory();
    }

    /** 清空她的记忆(界面在主人确认之后才调);办完她的对话从一个新的会话重新开始。拒绝的理由(语言键),办成是 {@code null}。 */
    public String requestWipe() {
        return host.wipeMemory();
    }

    /** 主人按停止。 */
    public void abort() {
        host.abort();
    }

    /** 定一个长期目标;{@code echo} 是主人打的原文,聊天里要有这行字。 */
    public void setGoal(GoalState next, String echo) {
        host.setGoal(next, echo);
    }

    /** 收工。 */
    public void clearGoal(String why) {
        host.clearGoal(why);
    }

    /** 把她分享给叫这个名字的玩家(界面在主人看过后果、确认之后才调);办不办得成由服务端判,结局回在聊天里。 */
    public void share(String player) {
        NumenNetwork.sendToServer(OwnerInputPayload.of(new OwnerInput.Share(uuid, player)));
    }

    /** 不再把她分享给这一位;被分享的人退出时 {@code person} 是他自己。 */
    public void unshare(UUID person) {
        NumenNetwork.sendToServer(OwnerInputPayload.of(new OwnerInput.Unshare(uuid, person)));
    }

    /** 换她的一项绑定(档案、人设、声线);{@code null} = 不绑。条目要在主人的库里。 */
    public void bind(Slot slot, String entryId) {
        EntryId id = entryId == null ? null : EntryId.parse(entryId);
        if (id != null && ClientCompanions.library(slot.kind()).find(id) == null) {
            return;
        }
        host.bind(slot, id == null ? null : new Bound(ClientCompanions.viewer(), id));
    }

    private static void onClientThread(Runnable task) {
        net.minecraft.client.Minecraft.getInstance().execute(task);
    }
}
