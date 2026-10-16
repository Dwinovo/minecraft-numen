package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.goal.GoalState;
import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.agent.provider.Usage;

import java.util.List;
import java.util.UUID;

/**
 * 界面看一只同伴、对她做事的唯一口:面板、快捷对话、斜杠命令、头顶气泡、档案页都经它。读的是此刻的样子(状态、
 * 在飞的半截回复、用量、目标、日志行),做的是主人想做的事(说话、停止、整理、清空、设目标、换人设和档案)。
 * 界面不知道她的大脑在哪:{@link RemoteCompanion} 的数据来自服务端、做的事发给服务端;主人客户端上还没搬走的旧循环
 * ({@link EntityAgentLoop})也实现它。
 *
 * <p>语言:这里没有翻译过的字。理由、原因、在做的事都是语言键(加现场的词),界面按主人的语言翻。
 */
public interface CompanionView {

    UUID entityUuid();

    // ---- 读 ----

    /** 内核此刻的只读快照:忙不忙、为什么不动、排着什么、在干什么。 */
    LoopStatus status();

    /** 在飞的回复已长出的正文(空串 = 没有):对话面板打字机的数据源。 */
    String livePartial();

    /** 在飞回合的思考流(空串 = 没有或已落库)。 */
    String liveReasoning();

    long totalTokensUsed();

    /** 四元累计用量(跨会话)。 */
    Usage usageTotals();

    /** 最近一轮的用量:命中率取自它,累计命中率会被历史稀释。 */
    Usage lastUsage();

    /** 缓存重计费的诊断数:本该命中却重新处理的 token,和发生的次数。 */
    long wastedTokens();

    int wasteCount();

    /** 这次工具调用的结果还会不会来;历史里没结果、这里又答 false 的调用是被切断的。 */
    boolean isToolCallOutstanding(String callId);

    /** 当前的长期目标;没有是 {@code null}。 */
    GoalState goal();

    /** 此刻不能整理记忆的理由(语言键);{@code null} = 能。 */
    String compactProblem();

    /** 此刻不能清空上下文的理由(语言键);{@code null} = 能。 */
    String clearProblem();

    /** 上下文水位百分比。 */
    int contextPercent();

    /** 她绑定的模型的上下文窗口;没绑是 0。 */
    int modelWindow();

    String personaId();

    /** 人设名(面板显示用);没绑或条目没了是 {@code null}。 */
    String personaName();

    /** 她绑定的模型档案 id;没绑是 {@code null}。 */
    String providerEntryId();

    /** 她此刻在哪个会话里(最后一次是被哪个会话叫醒的);{@code null} = 就他俩。 */
    String conversation();

    /** 她此刻的上下文里有几条消息。 */
    int messageCount();

    /** 她自己那本日志里已经读到手的面板行,从老到新。 */
    List<ConvoLog.Line> display();

    // ---- 做 ----

    /** 主人说话。返回这句话有没有被压着(内脑没能当场把请求发出去);只是观察。 */
    boolean submitPrompt(String text);

    /** 一条技能命令:{@code echo} 是主人打的原文;技能正文由大脑所在的一侧展开。 */
    void submitSkill(String echo, String skill, String args);

    /** 主人要求整理记忆。返回拒绝的理由(语言键);{@code null} = 已排上。 */
    String requestCompact();

    /** 主人要求清空上下文,形状同 {@link #requestCompact}。 */
    String requestClearContext();

    /** 主人按停止。 */
    void abort();

    /** 定一个长期目标;{@code echo} 是主人打的原文,聊天里要有这行字。 */
    void setGoal(GoalState next, String echo);

    /** 收工。 */
    void clearGoal(String why);

    /** 换人设;{@code null} = 默认人格。 */
    void setPersona(String id);

    /** 换模型档案;{@code null} = 不绑。 */
    void setProviderEntry(String entryId);
}
