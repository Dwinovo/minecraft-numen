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

    /** 重启后的活接回来没有(每具身体只接一次)。 */
    boolean restored;

    /** 这个大脑伺候的那具身体。首次 tick 时认下，以后不换。 */
    private NumenPlayer body;

    /** 上一刻的赢家,用来在切换的那一刻(而不是每刻)通知它丢了身体。 */
    private Task holder;

    /** 进度变化最快多久推一次(刻)。一秒足够"在动"的观感,又不至于每刻一个包。 */
    private static final int TASK_PUSH_GAP_TICKS = 20;

    /** 上一次推给主人的任务 id(""=闲着)。 */
    private String pushedTaskId = "";
    /** 上一次推给主人的那行人话——进度写在里面,它一变主人就该看见。 */
    private String pushedDescribe = "";
    /** 距上次推过了几刻。 */
    private int ticksSinceTaskPush;

    /** 手部占用的空闲边沿(纯计数器,见 {@link #HAND_PIN_GRACE_TICKS})。 */
    private final HandPinRelease handPinRelease = new HandPinRelease(HAND_PIN_GRACE_TICKS);

    CompanionBrain() {
        this.reflexes = List.copyOf(BrainChains.build());
        // 说话时看着主人排在当前任务<b>之下</b>——她挖着矿跟你说话不回头,
        // 这是旧调度里就有的关系(它的出价本来就低于 LLM 基准价),不是新决定。
        this.idlePoses = List.of(new com.dwinovo.numen.task.chain.SpeakingLookChain());
    }

    /**
     * 还是不是同一具身体。
     *
     * <p>同伴的 UUID 跨存档不变，身体实例却是新的。而任务把身体记在自己身上
     * ({@code AbstractCompanionTask.player} 是 final 的)，所以一个大脑<b>不能跨身体复用</b>：
     * 旧任务会拿旧 {@code ServerLevel} 去读方块，而那个 level 记的主线程早就结束了，
     * {@code getChunk} 会把活派进一个再也没人抽取的队列然后 {@code join()} —— 新 Server thread
     * 永久 park，世界再也加载不出来。
     *
     * <p>正常情况下关服就会把整表作废({@code ServerLifecycle})，轮不到这里。它是不变式本身：
     * 哪天又有哪张静态表漏了，最坏是大脑重建，不是服务端线程死锁。
     */
    boolean boundTo(NumenPlayer companion) {
        return body == null || body == companion;
    }

    /** 绑着的那具身体已经离开世界了 —— 这才是"该换大脑"。 */
    boolean boundBodyGone() {
        return body == null || body.isRemoved();
    }

    void tick(NumenPlayer companion) {
        body = companion;
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
        // 手上的活干完了就把记录抹掉,免得重启后凭空捡回一件早就完成的活。
        if (current.isEmpty() && !wasIdle) {
            TaskPersistence.forget(companion);
        }
        wasIdle = current.isEmpty();
        shipResults(companion);
        syncCurrentTask(companion);
    }

    /**
     * 把「她现在在做什么」推给主人——<b>槽变了才推</b>。
     *
     * <p>叫在每个改动槽的地方(这里、死亡丢弃、离场结算),因为槽的转换只发生在本类里,
     * 所以这就是那件事的唯一出口:派发、重放、被顶替、干完、死亡清空,客户端看到的是
     * 同一条消息。从前客户端自己记账,只认得它<b>自己派出去</b>的那次调用,于是重放回来
     * 的活它一概不知道——头顶没气泡,{@code <runtime_state>} 里也说她闲着。
     */
    private void syncCurrentTask(NumenPlayer companion) {
        TaskRecord rec = current.record();
        String id = rec == null ? "" : rec.publicId();
        String desc = rec == null ? "" : rec.describe();
        ticksSinceTaskPush++;
        // 换活立刻推;同一件活只有那行人话变了才推(进度就写在里面),而且限速——
        // 不限速的话「挖 7/64」每刻都在变,一秒 20 个包;只在换活时推又会让进度
        // 永远停在受理那一刻,主人看着像卡住了。
        boolean swapped = !id.equals(pushedTaskId);
        if (!swapped && (desc.equals(pushedDescribe) || ticksSinceTaskPush < TASK_PUSH_GAP_TICKS)) {
            return;
        }
        pushedTaskId = id;
        pushedDescribe = desc;
        ticksSinceTaskPush = 0;
        net.minecraft.server.level.ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner == null) {
            return;   // 主人不在线:他重登时会看到那时的状态,不需要补发历史
        }
        com.dwinovo.numen.network.payload.CurrentTaskPayload msg;
        if (rec == null) {
            msg = com.dwinovo.numen.network.payload.CurrentTaskPayload.idle(companion.getUUID());
        } else {
            long startedTick = Math.max(0, rec.getStartedGameTime());
            long elapsedMs = Math.max(0, companion.level().getGameTime() - startedTick) * 50L;
            msg = new com.dwinovo.numen.network.payload.CurrentTaskPayload(
                    companion.getUUID(), id, rec.getToolName(), desc,
                    rec.getDeadlineGameTime() >= TaskRecord.NO_DEADLINE, elapsedMs);
        }
        com.dwinovo.numen.platform.Services.NETWORK.sendToPlayer(owner, msg);
    }

    /** 上一刻当前任务槽是不是空的——用来只在"刚变空"那一刻清记录,不必每刻写盘。 */
    private boolean wasIdle = true;

    /** 死亡:丢掉在跑的任务,并结束任务作用域的手部占用。 */
    void dropActiveNoResult(NumenPlayer companion) {
        TaskSessionHooks.fireSessionEnd(companion);
        sync.dropNoResult(companion);
        current.dropNoResult(companion);
        holder = null;
        // 死亡把这件活的前提一并带走了:工具和材料掉在尸体旁,位置从矿洞变成了主人身边。
        // 所以登记也得抹掉 —— 不抹的话复活后的新大脑会照着旧登记重放一遍
        // (TaskPersistence 是给"身体没了但意图还在"写的:重启、休眠、换肤重建),
        // 于是她空着手回去接着挖,而模型刚收到的是 task_finished(interrupted)。
        // 要不要接着干由她自己看了事件再决定,不由代码替她决定。
        TaskPersistence.forget(companion);
        syncCurrentTask(companion);
    }

    /** 身体离开世界:两个槽就地结算(它们不会再被 tick),结果照送。 */
    void finalizeActive(NumenPlayer companion) {
        sync.finalizeInline();
        current.finalizeInline();
        shipResults(companion);
        holder = null;
        syncCurrentTask(companion);
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
