package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.agent.inbox.ExternalWait;
import com.dwinovo.numen.api.agent.llm.ToolOutcome;
import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.agent.loop.RunEnd;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ScriptTool;
import com.dwinovo.numen.api.agent.tool.ToolBook;
import com.dwinovo.numen.api.mcp.server.ExternalHands;
import com.dwinovo.numen.api.mcp.server.McpChannels;
import com.dwinovo.numen.api.permission.ConsentAnswer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 外部大脑的端点伸向她们的手,主人客户端上的唯一一份:直接操作她们的大脑({@link LoopHost}),召唤与遣散走面板同一条路({@link Actions})。
 * 外脑做的一切都由 {@link LoopHost} 记进她的日志,面板读日志。
 *
 * <p>线程:每个方法可在任意线程调,工作切到客户端主线程({@code main}),结果经 future 回来;端点自己的线程在 future 上等,
 * 主线程上不许等({@link #onMainThread})。
 */
public final class LoopHands implements ExternalHands {

    /** 外脑跑的调用的 id,和模型自己的 id 不重。 */
    private static final String CALL_PREFIX = "ext-";
    private static final AtomicLong SEQ = new AtomicLong();

    /** 召唤与遣散:面板上"+"与"✕"走的那两条路,发给服务端。 */
    public interface Actions {

        /** 按名字召唤一只;发出去了为 true,此刻没有连着世界为 false。 */
        boolean summon(String name);

        /** 遣散一只;发出去了为 true。 */
        boolean dismiss(UUID companion);
    }

    private final ClientBrains brains;
    private final Executor main;
    private final BooleanSupplier onMain;
    private final ToolBook tools;
    private final Supplier<List<Companion>> roster;
    private final Actions actions;
    private final ConsentVenues venues;
    /** 渠道来访着的同伴:她的回话与征询回到那个渠道,直到她这一轮说完。 */
    private final Map<UUID, McpChannels.Channel> visits = new ConcurrentHashMap<>();
    private final Set<UUID> listening = ConcurrentHashMap.newKeySet();

    /**
     * @param brains 这条连接上的大脑
     * @param main   客户端主线程
     * @param onMain 调用的线程是不是客户端主线程
     * @param tools  她看得到的工具
     * @param roster 主人名下此刻在世界里的同伴
     */
    public LoopHands(ClientBrains brains, Executor main, BooleanSupplier onMain, ToolBook tools,
                     Supplier<List<Companion>> roster, Actions actions, ConsentVenues venues) {
        this.brains = brains;
        this.main = main;
        this.onMain = onMain;
        this.tools = tools;
        this.roster = roster;
        this.actions = actions;
        this.venues = venues;
        venues.register(new ConsentVenues.Venue() {
            @Override
            public void show(ConsentVenues.Offer offer) {
                McpChannels.Channel channel = visits.get(offer.companion());
                if (channel != null) {
                    channel.ask(offer);
                }
            }

            @Override
            public void clear(long id, com.dwinovo.numen.api.permission.ConsentDesk.Withdrawal why) {
                visits.values().forEach(channel -> channel.clear(id, why));
            }
        });
    }

    private <T> CompletableFuture<T> onMain(java.util.function.Supplier<T> job) {
        CompletableFuture<T> f = new CompletableFuture<>();
        main.execute(() -> {
            try {
                f.complete(job.get());
            } catch (RuntimeException failed) {
                f.completeExceptionally(failed);
            }
        });
        return f;
    }

    @Override
    public boolean onMainThread() {
        return onMain.getAsBoolean();
    }

    @Override
    public CompletableFuture<List<NumenTool>> tools() {
        return onMain(() -> tools.all().stream().filter(tool -> !tool.waitsForOwner()).toList());
    }

    @Override
    public CompletableFuture<String> guide(UUID companion) {
        return onMain(() -> {
            LoopHost host = brains.get(companion);
            return host == null ? null : host.systemPrompt();
        });
    }

    @Override
    public CompletableFuture<List<Companion>> companions() {
        return onMain(() -> roster.get().stream().filter(c -> brains.get(c.uuid()) != null).toList());
    }

    @Override
    public CompletableFuture<Boolean> create(String name) {
        return onMain(() -> actions.summon(name == null ? "" : name.trim()));
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID companion) {
        return onMain(() -> companion != null && actions.dismiss(companion));
    }

    @Override
    public CompletableFuture<String> takeEvents(UUID companion) {
        return onMain(() -> {
            LoopHost host = brains.get(companion);
            return host == null ? null : host.takeEventsForExternal();
        });
    }

    @Override
    public CompletableFuture<String> runtimeState(UUID companion) {
        CompletableFuture<String> f = new CompletableFuture<>();
        main.execute(() -> {
            LoopHost host = brains.get(companion);
            if (host == null) {
                f.complete(null);
            } else {
                host.readState().whenComplete((state, failed) -> {
                    if (failed != null) {
                        f.completeExceptionally(failed);
                    } else {
                        f.complete(state);
                    }
                });
            }
        });
        return f;
    }

    @Override
    public ExternalWait.Parked awaitEvents(UUID companion) {
        // 先查后登记在一次主线程的跳转里完成,不让一条事件从"队是空的"与"已停靠"之间溜过去
        return ExternalWait.parkOn(main, () -> {
            LoopHost host = brains.get(companion);
            return host == null ? null : host.parkForExternal();
        });
    }

    @Override
    public CompletableFuture<Boolean> say(UUID companion, String text) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        return onMain(() -> {
            LoopHost host = brains.get(companion);
            if (host == null) {
                return false;
            }
            host.externalSay(text);
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> hear(UUID companion, String text, McpChannels.Channel channel) {
        return onMain(() -> {
            LoopHost host = brains.get(companion);
            if (host == null) {
                return false;
            }
            // 先开来访再送话:话一进收件箱内核就可能开轮
            visits.put(companion, channel);
            if (listening.add(companion)) {
                host.visiting(() -> visits.containsKey(companion));
                host.subscribe(event -> heard(companion, event));
            }
            host.submitPrompt(text);
            return true;
        });
    }

    /** 来访着的渠道收她说的话;她这一轮说完了,来访就收了。 */
    private void heard(UUID companion, LoopEvent event) {
        McpChannels.Channel channel = visits.get(companion);
        if (channel == null) {
            return;
        }
        if (event instanceof LoopEvent.AssistantMessage message) {
            String said = message.turn().content() == null ? "" : message.turn().content().strip();
            if (!said.isEmpty()) {
                channel.said(companion, said);
            }
        } else if (event instanceof LoopEvent.RunEnded ended && ended.end() instanceof RunEnd.Done) {
            visits.remove(companion, channel);
        }
    }

    @Override
    public CompletableFuture<Boolean> answer(UUID companion, long id, ConsentAnswer.Decision decision, String note) {
        return onMain(() -> venues.answer(companion, id, decision, note));
    }

    @Override
    public CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson) {
        CompletableFuture<String> f = new CompletableFuture<>();
        if (companion == null || toolName == null || toolName.isBlank()) {
            f.complete(ToolOutcome.failure("companion and toolName are required"));
            return f;
        }
        main.execute(() -> {
            try {
                NumenTool tool = tools.resolve(toolName);
                if (tool == null || tool.waitsForOwner()) {
                    f.complete(ToolOutcome.failure("unknown tool: " + toolName));
                    return;
                }
                LoopHost host = brains.get(companion);
                if (host == null) {
                    f.complete(ToolOutcome.failure("no such companion: " + companion));
                    return;
                }
                String args = argsJson == null || argsJson.isBlank() ? "{}" : argsJson;
                if (tool instanceof ScriptTool) {
                    ScriptTool.parse(args);   // 参数写错当场回失败,不占工具口
                }
                LlmToolCall call = new LlmToolCall(CALL_PREFIX + SEQ.incrementAndGet(), tool.name(), args);
                if (!host.runExternal(call, f::complete)) {
                    f.complete(ToolOutcome.failure("a call is already running for this companion, or its "
                            + "built-in brain is acting; wait for it to end"));
                }
            } catch (RuntimeException ex) {
                f.complete(ToolOutcome.failure(ex.getMessage()));
            }
        });
        return f;
    }
}
