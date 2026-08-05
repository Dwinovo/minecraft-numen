package com.dwinovo.numen.task;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 一具身体的调度器——{@code CompanionTickDispatcher} 按 UUID 存的那个值。
 *
 * <p>每刻问一次谁拿身体({@link TaskSelector}),然后把完成的结果送出去。
 *
 * <pre>
 * 1. 反射     多个,按注册号(小的先)   等模型就晚了(摔落/换气/自卫/进食/脱困)
 * 2. 同步     0 或 1 个                回合挂着等它,有界短
 * 3. 当前任务 0 或 1 个                主人说什么就是什么
 * 4. 空闲姿态 多个                     没别的事可做时(说话时看着主人)
 * </pre>
 *
 * <h2>没有链、没有优先级数字</h2>
 * 从前每个行为都想当一条"链",链既是竞价单位又是行为单位,于是每加一种常驻行为
 * 就多一条链、多一个要调的浮点数。现在层是固定的三层,层内除了反射都最多一个
 * 候选——<b>没有任何需要比较的地方</b>;反射之间的先后写在注册号上,与原版
 * {@code addGoal(int priority, goal)} 同一惯例。
 *
 * <p>常驻和一次性也不再是两种东西:同一个 {@link Task} 放进第 3 层,
 * {@code tick()} 返终态就是有界的(干完腾位),永远返 RUNNING 就是常驻的
 * (占着直到主人换掉它)。
 */
final class CompanionBrain {

    /**
     * 任务闲下来多久之后,主手的意图占用自动松开(宪法 §5 / 第 11 条)。
     * 用去抖而不是裸边沿:客户端严格串行地派工具,一个回合里两次调用之间链是空的,
     * 空多久取决于模型想多久;30 秒足够盖住那个间隙,又能在活真干完后不久放手。
     */
    private static final int HAND_PIN_GRACE_TICKS = 600;

    /** 结算完等着送回主人的记录。 */
    private final Deque<TaskRecord> outbox = new ArrayDeque<>();

    /** 回合挂着等的同步动作。 */
    final TaskSlot sync = new TaskSlot(outbox::addLast);
    /** 她现在在做的事:钓鱼、跟随、挖 64 块——常驻还是一次性只看 tick 返不返终态。 */
    final TaskSlot current = new TaskSlot(outbox::addLast);

    private final List<Task> reflexes;
    /** 空闲姿态:没别的事做时才轮到,连身体都不算真正占用。 */
    private final List<Task> idlePoses;

    /** 上一刻的赢家,用来在切换的那一刻(而不是每刻)通知它丢了身体。 */
    private Task holder;

    /** 手部占用的空闲边沿(纯计数器,见 {@link #HAND_PIN_GRACE_TICKS})。 */
    private final HandPinRelease handPinRelease = new HandPinRelease(HAND_PIN_GRACE_TICKS);

    CompanionBrain() {
        this.reflexes = List.copyOf(BrainChains.build());
        // 说话时看着主人排在当前任务<b>之下</b>——她挖着矿跟你说话不回头,
        // 这是旧调度里就有的关系(它的出价本来就低于 LLM 基准价),不是新决定。
        this.idlePoses = List.of(new com.dwinovo.numen.task.chain.SpeakingLookChain());
    }

    void tick(NumenPlayer companion) {
        // 任务结束边沿(宪法 §5):两个槽都空过了宽限窗口,显式占用的会话就结束了,
        // 手还给反射。护甲的占用不动(它的生命是 §5 的四个自然终点),只有主手是任务作用域的。
        if (handPinRelease.tick(!sync.isEmpty() || !current.isEmpty())) {
            TaskSessionHooks.fireSessionEnd(companion);
        }

        Task winner = TaskSelector.select(reflexes, slotTask(sync, companion),
                slotTask(current, companion), idlePoses, companion);

        if (holder != null && holder != winner) {
            holder.stop(companion, Task.StopReason.PREEMPTED);
        }
        holder = winner;

        // 没轮到的槽这一刻不烧预算——身体被别人占着不是它的错。
        if (winner != syncProxy) {
            sync.freeze();
        }
        if (winner != currentProxy) {
            current.freeze();
        }

        if (winner == syncProxy) {
            sync.tick(companion);
        } else if (winner == currentProxy) {
            current.tick(companion);
        } else if (winner != null) {
            winner.tick(companion);
        }

        // 每刻结算:主人按停止会在带外把记录标成终态,而客户端串行的派发器会一直
        // 卡到那一个结果送出——不能等这个槽下次赢了才结算。
        sync.settleIfTerminal();
        current.settleIfTerminal();
        shipResults(companion);
    }

    /** 死亡:丢掉在跑的任务,并结束任务作用域的手部占用。 */
    void dropActiveNoResult(NumenPlayer companion) {
        TaskSessionHooks.fireSessionEnd(companion);
        sync.dropNoResult(companion);
        current.dropNoResult(companion);
        holder = null;
    }

    /** 身体离开世界:两个槽就地结算(它们不会再被 tick),结果照送。 */
    void finalizeActive(NumenPlayer companion) {
        sync.finalizeInline();
        current.finalizeInline();
        shipResults(companion);
        holder = null;
    }

    // ---- 槽的代理:让选择器能像问一个 Task 那样问一个槽 ----

    private final Task syncProxy = new SlotProxy(() -> sync);
    private final Task currentProxy = new SlotProxy(() -> current);

    private Task slotTask(TaskSlot slot, NumenPlayer companion) {
        if (slot.isEmpty()) {
            return null;
        }
        return slot == sync ? syncProxy : currentProxy;
    }

    /**
     * 把一个槽包成 {@link Task} 给选择器看。
     *
     * <p>选择器只认识"能不能跑",不该知道槽是什么;而槽的 tick 需要结算、送结果,
     * 不是一个纯粹的 Task。这层薄包装让两边各自保持简单。
     */
    private static final class SlotProxy implements Task {
        private final java.util.function.Supplier<TaskSlot> slot;

        SlotProxy(java.util.function.Supplier<TaskSlot> slot) {
            this.slot = slot;
        }

        @Override public boolean canRun(NumenPlayer companion) {
            return slot.get().canRun(companion);
        }

        @Override public TaskState tick(NumenPlayer companion) {
            slot.get().tick(companion);
            return TaskState.RUNNING;
        }

        @Override public void stop(NumenPlayer companion, StopReason why) {
            slot.get().loseBody(companion);
        }

        @Override public String name() {
            return "slot";
        }
    }

    /**
     * 把结算好的记录送回主人。
     *
     * <p>主人离线时<b>异步任务的收尾照发</b>——它走 {@link com.dwinovo.numen.event.NumenEvents},
     * 自己会进出箱等主人回来。只有同步 tool_call 的结果没处送(那条调用属于一个
     * 随客户端一起消失的回合),重登时由 {@code unansweredToolCallIds} 收口。
     */
    private void shipResults(NumenPlayer companion) {
        if (outbox.isEmpty()) {
            return;
        }
        net.minecraft.server.level.ServerPlayer owner = companion.resolveOwnerPlayer();
        while (!outbox.isEmpty()) {
            TaskRecord rec = outbox.pollFirst();
            TaskResult result = rec.getResult();
            if (rec.isAsync()) {
                // 外部(MCP)派的异步任务不投 task_finished:那条事件会唤醒并没有派它的
                // 内置大脑。外部驱动靠 task_status 轮询 + 感知确认闭环。
                if (rec.isExternalCall()) {
                    continue;
                }
                String status = switch (rec.getState()) {
                    case SUCCESS -> "done";
                    case TIMEOUT -> "timeout";
                    case CANCELLED -> "stopped";
                    default -> "failed";
                };
                String msg = result == null ? "no result produced" : result.message();
                com.dwinovo.numen.event.NumenEvents.taskFinished(
                        companion, rec.publicId(), rec.getToolName(), status, msg);
                continue;
            }
            if (owner == null) {
                continue;
            }
            String json = result == null
                    ? "{\"success\":false,\"message\":\"no result produced\"}"
                    : result.toJson();
            com.dwinovo.numen.platform.Services.NETWORK.sendToPlayer(owner,
                    new com.dwinovo.numen.network.payload.TaskResultPayload(
                            companion.getUUID(), rec.getToolCallId(), json));
        }
    }
}
