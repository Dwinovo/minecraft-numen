package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.tool.ClientToolContext;
import com.dwinovo.numen.agent.tool.CompanionToolPort;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The loop kernel's {@link ToolPort} for one companion on the owner's client. How the calls run — in order,
 * one at a time, each bound to this companion — is {@link CompanionToolPort}'s, the same one the bench brain
 * uses; the tool context here carries the live client-side body. What only the owner's client has is the
 * backstop: a call that never replies is failed after {@link #TOOL_BACKSTOP_MILLIS}, unless the body is
 * waiting on the owner's consent card.
 */
public final class ToolDispatcher implements ToolPort {

    /**
     * Wall-clock backstop for the single in-flight call: the longest a program may run
     * ({@link com.dwinovo.numen.agent.script.ScriptLimits#WALL_MILLIS}, which the server enforces itself) plus five
     * minutes of slack. It only rescues a result that was lost — a server that died, a connection that dropped — so
     * a program that is merely long never trips it.
     */
    private static final long TOOL_BACKSTOP_MILLIS =
            com.dwinovo.numen.agent.script.ScriptLimits.WALL_MILLIS + 5 * 60 * 1000L;

    private final UUID entityUuid;
    private final CompanionToolPort tools;

    /** The in-flight call the backstop is timing, and when it runs out (epoch millis); null when idle. */
    private LlmToolCall timed;
    private long deadlineMillis = 0;

    /** @param entity the live client-side body (for client-run tools); may resolve to null when out of view */
    public ToolDispatcher(UUID entityUuid, Supplier<AbstractClientPlayer> entity, CompanionToolPort.AfterCut afterCut) {
        this.entityUuid = entityUuid;
        this.tools = new CompanionToolPort(entityUuid, () -> new ClientToolContext(entity.get(), entityUuid), afterCut);
    }

    /** Is this call still outstanding (in flight or still queued), i.e. can its result still arrive? */
    public boolean holds(String callId) {
        return tools.holds(callId);
    }

    /** 手上这一件的工具名(在飞的,没有就是下一个要派的),空闲返回 null——头顶气泡的副文本取它。 */
    public String currentToolName() {
        return tools.currentToolName();
    }

    /** 收下这一批调用,按顺序执行。 */
    @Override
    public void run(List<LlmToolCall> batch, Sink sink) {
        tools.run(batch, sink);
    }

    @Override
    public void arrived(EventQueue.Entry entry, boolean urgent) {
        tools.arrived(entry, urgent);
    }

    /**
     * Per-tick backstop: fail a never-replying in-flight call so the loop can't wedge. The clock starts the
     * first tick a call is seen in flight.
     *
     * <p>这具身体挂着一条等主人点头的征询时不算:服务端活着、正在等人,在飞的那件同步动作
     * (use block 左键、inv drop)就是悬着等这个答复,由服务端按游戏刻超时收尾。兜底时钟
     * 从答复之后重新起算。等身体任务收尾不在这里:那件活有自己的期限,到点以 timeout 收尾。
     */
    public void tick() {
        LlmToolCall call = tools.inFlight();
        if (call == null) {
            timed = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (call != timed || com.dwinovo.numen.client.consent.ConsentCards.pending(entityUuid) != null) {
            timed = call;
            deadlineMillis = now + TOOL_BACKSTOP_MILLIS;
            return;
        }
        if (now < deadlineMillis) return;
        Constants.LOG.warn("[numen-dispatch#{}] tool {} id={} hit backstop timeout — failing it",
                entityUuid, call.name(), call.id());
        tools.failInFlight("tool timed out (no result returned)");
    }

    /**
     * 收掉这批所有未决调用(在飞 + 排着),返回它们的 id。
     *
     * @param stopBody 要不要连身体一起叫停。<b>主人按停止</b>要——他要她立刻住手;死亡、断线登出、
     *                 外接接管、遣散不要——死了不必叫,登出时她的身体还在服务器里、任务照样该跑完,
     *                 而且那一刻连接已经没了,叫停包根本发不出去
     */
    @Override
    public List<String> cancel(boolean stopBody) {
        timed = null;
        return tools.cancel(stopBody);
    }
}
