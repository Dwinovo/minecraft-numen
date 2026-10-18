package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.agent.inbox.ExternalWait;
import com.dwinovo.numen.api.agent.llm.ToolOutcome;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ScriptTool;
import com.dwinovo.numen.api.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.brain.CompanionLoop;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.brain.CompanionLoops;
import com.dwinovo.numen.api.brain.OwnerInput;
import com.dwinovo.numen.api.brain.OwnerInputs;
import com.dwinovo.numen.api.brain.Visits;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.ConsentAnswer;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.dwinovo.numen.api.network.payload.DismissRequestPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同伴的大脑在服务端时外脑的手:直接操作进程内的 {@link CompanionLoop}。外脑驱动的是服主名下的同伴
 * (单人游戏就是房主),召唤与遣散走面板同一条路。
 *
 * <p>每个方法可在任意线程调,工作切到服务端主线程。外脑做的一切都由 {@link CompanionLoop} 记进她的日志,
 * 所以 {@link ExternalHands#activity} 在这里不需要。
 */
public final class ServerActuator implements ExternalHands {

    /** 外脑跑的调用的 id,和模型自己的 id 不重。 */
    private static final String CALL_PREFIX = "ext-";
    private static final AtomicLong SEQ = new AtomicLong();

    private final MinecraftServer server;
    private final UUID owner;

    public ServerActuator(MinecraftServer server, UUID owner) {
        this.server = server;
        this.owner = owner;
    }

    @Override
    public CompletableFuture<List<Companion>> companions() {
        CompletableFuture<List<Companion>> f = new CompletableFuture<>();
        server.execute(() -> {
            List<Companion> out = new ArrayList<>();
            for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(owner)) {
                out.add(new Companion(e.getKey(), e.getValue().name()));
            }
            f.complete(out);
        });
        return f;
    }

    @Override
    public CompletableFuture<Boolean> create(String name) {
        return asOwner(player -> Companions.summonFor(player, name == null ? "" : name.trim(), null, summoned -> {
            if (summoned.problem() != null) {
                player.sendSystemMessage(summoned.problem());
            }
        }));
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID companion) {
        if (companion == null) {
            return CompletableFuture.completedFuture(false);
        }
        return asOwner(player -> DismissRequestPayload.handle(new DismissRequestPayload(companion), player));
    }

    /** 主人在线才有人替他召唤、遣散(这两条路的权威校验都认一个 {@link ServerPlayer})。 */
    private CompletableFuture<Boolean> asOwner(java.util.function.Consumer<ServerPlayer> job) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) {
                f.complete(false);
                return;
            }
            job.accept(player);
            f.complete(true);
        });
        return f;
    }

    @Override
    public CompletableFuture<String> takeEvents(UUID companion) {
        CompletableFuture<String> f = new CompletableFuture<>();
        server.execute(() -> f.complete(CompanionLoops.get(companion)
                .map(CompanionLoop::takeEventsForExternal).orElse(null)));
        return f;
    }

    @Override
    public ExternalWait.Parked awaitEvents(UUID companion) {
        // 先查后登记在一次主线程的跳转里完成,不让一条事件从"队是空的"与"已停靠"之间溜过去
        return ExternalWait.parkOn(server, () -> CompanionLoops.get(companion)
                .map(CompanionLoop::parkForExternal).orElse(null));
    }

    @Override
    public CompletableFuture<Boolean> say(UUID companion, String text) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        server.execute(() -> f.complete(CompanionLoops.get(companion).map(loop -> {
            loop.externalSay(text);
            return true;
        }).orElse(false)));
        return f;
    }

    @Override
    public CompletableFuture<Boolean> hear(UUID companion, String text, Visits.Channel channel) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        server.execute(() -> {
            // 先开来访再送话:话一进收件箱内核就可能开轮,那一刻主人必须已经算在场
            Visits.begin(companion, owner, channel);
            boolean sent = OwnerInputs.apply(server, owner,
                    new OwnerInput.Emit(companion, EventTypes.QUERY, text)).problem() == null;
            if (!sent) {
                Visits.end(companion);
            }
            f.complete(sent);
        });
        return f;
    }

    @Override
    public CompletableFuture<ConsentDesk.Reply> answer(UUID companion, long id, ConsentAnswer.Decision decision,
                                                       String note) {
        CompletableFuture<ConsentDesk.Reply> f = new CompletableFuture<>();
        server.execute(() -> {
            NumenPlayer body = NumenPlayer.findByUuid(server, companion);
            f.complete(body == null ? ConsentDesk.Reply.NOT_PENDING
                    : ConsentDesk.reply(owner, body, id, decision, note));
        });
        return f;
    }

    @Override
    public CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson) {
        CompletableFuture<String> f = new CompletableFuture<>();
        if (companion == null || toolName == null || toolName.isBlank()) {
            f.complete(ToolOutcome.failure("companion and toolName are required"));
            return f;
        }
        server.execute(() -> {
            try {
                NumenTool tool = ToolRegistry.resolve(toolName);
                if (tool == null) {
                    f.complete(ToolOutcome.failure("unknown tool: " + toolName));
                    return;
                }
                CompanionLoop loop = CompanionLoops.get(companion).orElse(null);
                if (loop == null) {
                    f.complete(ToolOutcome.failure("no such companion: " + companion));
                    return;
                }
                String args = argsJson == null || argsJson.isBlank() ? "{}" : argsJson;
                if (tool instanceof ScriptTool) {
                    ScriptTool.code(args);   // 参数写错当场回失败,不占工具口
                }
                LlmToolCall call = new LlmToolCall(CALL_PREFIX + SEQ.incrementAndGet(), tool.name(), args);
                if (!loop.runExternal(call, f::complete)) {
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
