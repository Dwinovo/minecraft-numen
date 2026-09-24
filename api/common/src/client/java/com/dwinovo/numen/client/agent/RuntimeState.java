package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.data.ClientNumenState;

import java.util.UUID;

/**
 * 每一轮临时挂在请求里的 {@code <runtime_state>}:她此刻在做的活、背包、身上的效果、骑没骑着东西、
 * 身上穿戴的({@code <worn>})、插件从身体上读的片段。全部现算,一个字都不入会话历史——这些都会变,进了历史就是理直气壮的旧数。
 *
 * <p>她在做的那件活是<b>服务端推来的镜像</b>({@link #onCurrentTask}),头顶气泡的副文本、停止键亮不亮、
 * 目标续跑要不要让位也都读它,所以镜像只在这里存一份。
 */
final class RuntimeState {

    private final UUID entityUuid;

    /** 她此刻在做的那件后台活;{@code null} = 身体空闲。 */
    private CurrentTask currentTask;

    /**
     * 她此刻在做什么——<b>服务端推来的镜像</b>,不是本地推断的账本
     * (见 {@link com.dwinovo.numen.network.payload.CurrentTaskPayload})。{@code standing} = 这件活没有终点
     * (不会有 task_finished),只能被换掉——模型必须分得清,否则会干等一个永不到来的事件。
     */
    private record CurrentTask(String id, String tool, String describe, long sinceMs,
                               boolean standing) {}

    RuntimeState(UUID entityUuid) {
        this.entityUuid = entityUuid;
    }

    /**
     * 服务端说她在做什么——直接照抄,不判断、不合并、不推断。
     *
     * <p>这是 {@code currentTask} 的写入点(按停止与断线时清掉本地镜像除外,见 {@link #on})。
     * 客户端不靠"我派出去过什么"自己记账:那样服务器重启重放、死亡复活重放起来的活它一概不知道,
     * 头顶没气泡、模型也看不见。
     */
    void onCurrentTask(com.dwinovo.numen.network.payload.CurrentTaskPayload p) {
        if (p.idle()) {
            currentTask = null;
            return;
        }
        // 用服务端给的已耗时回推起点,重放回来的活也不会从这一刻重新计时
        currentTask = new CurrentTask(p.taskId(), p.tool(), p.describe(),
                System.currentTimeMillis() - p.elapsedMs(), p.standing());
    }

    /**
     * 内核的事件里这边要接的:主人按停止、断线时清掉本地镜像。停止时服务端随后会推 idle,这里先清,停止键当场灭;
     * 断线时下一个存档跟这件活无关,而那时不会有服务端推送来纠正它。
     */
    void on(com.dwinovo.numen.agent.loop.LoopEvent event) {
        if (event instanceof com.dwinovo.numen.agent.loop.LoopEvent.Halted halted
                && (halted.reason() == com.dwinovo.numen.agent.loop.HaltReason.OWNER_STOP
                || halted.reason() == com.dwinovo.numen.agent.loop.HaltReason.DISCONNECT)) {
            currentTask = null;
        }
    }

    /** 身体手上有没有后台活。 */
    boolean bodyTaskRunning() {
        return currentTask != null;
    }

    /** 身体手上有一件会结束的活(常驻的跟随这种不算:它永远不报完成)。 */
    boolean bodyOnFiniteTask() {
        return currentTask != null && !currentTask.standing();
    }

    /**
     * 她手上那件活给人看的一句:服务端给的人话描述("挖 64 块泥土"),没有才退到工具 id;没有活返回 {@code null}。
     * 气泡是给主人看的,他不该在头顶上读内部标识符。
     */
    String activity() {
        if (currentTask == null) {
            return null;
        }
        String d = currentTask.describe();
        return d != null && !d.isBlank() ? d : currentTask.tool();
    }

    /**
     * 这一轮临时挂载的运行期状态。全部现算,一个字都不入会话历史——包进同一个
     * {@code <runtime_state>} 里,模型只需认一个信封。
     */
    String xml() {
        // 插件的片段也挂这一层:它们和背包、状态效果一样是"此刻的她",
        // 会变,所以不能进字节级稳定的系统提示。身体上的那段随状态包从服务端来,
        // 只有这个客户端才知道的那段在这里现算。
        String body = currentTaskXml() + inventoryXml() + effectsXml() + ridingXml() + bodyStateXml()
                + com.dwinovo.numen.api.NumenPlugins.stateFragments(entityUuid);
        String xml = body.isEmpty() ? "" : "<runtime_state>" + body + "</runtime_state>";
        // 原样打出来。"她看到的世界"平时完全不可见,于是"她怎么会这么说"只能靠猜——
        // 而她说的数跟事件对不上时,分不清是她编的还是我们喂错了。开一次 debug 就有答案。
        //
        // (靠它抓到过一次:任务完成事件和 <current_task> 镜像在同一条请求里打架,
        //  镜像还停在旧进度,于是她照着旧数说"还差一点"。)
        Constants.LOG.debug("[numen-ctx#{}] runtime_state → {}", entityUuid, xml);
        return xml;
    }

    /** Live async-task state, recomputed for every worker request and never persisted. */
    private String currentTaskXml() {
        CurrentTask task = currentTask;
        if (task == null) return "";
        long elapsed = Math.max(0, System.currentTimeMillis() - task.sinceMs()) / 1000;
        // 有没有"干完"这回事,决定她该等还是该换:有终点的活等它的 task_finished;
        // 常驻的活(跟随 / 一直钓鱼)永远不会有那条事件,只能被换掉。分不清这一点,
        // 她要么干等一个永不到来的事件,要么把还没干完的活当成已经结束。
        // 两支只差在「会不会有 task_finished」。怎么换是一样的 —— 直接派新的。
        String tail = task.standing()
                ? "This is a STANDING job — it has no finish line and will NEVER send a "
                  + "task_finished event. It keeps running until something replaces it."
                : "This background call is ACTIVE and will send a task_finished event when it ends; "
                  + "use task_status only when the owner asks for progress.";
        // 身体只有一个槽，派新活自然顶掉旧活，所以这里必须说「直接派」而不是
        // 「别再派」——后者会让模型先 task_stop 再派，白跑一轮。
        // 只有「停下来什么也不干」才需要 task_stop。
        String swap = " There is only ONE body: dispatching another body action REPLACES this one "
                + "outright — you do NOT need to stop it first. Use task_stop only when the owner "
                + "wants her to stop and do nothing.";
        return "<current_task id=\"" + xml(task.id()) + "\" tool=\""
                + xml(task.tool()) + "\" state=\"running\" standing=\"" + task.standing()
                + "\" elapsed_s=\"" + elapsed
                + "\">" + xml(truncate(task.describe(), 600)) + ". "
                + tail + swap + "</current_task>";
    }

    /** 上一次渲染背包块用的那份快照本身。收到新包时缓存会换一个新对象,比身份就够,
     *  不用拿时间戳去凑版本号(同一毫秒两次推送会撞号,而且读起来像在判断时效)。 */
    private ClientNumenState.Snapshot inventoryRenderedFrom;
    private String inventoryRendered = "";
    /** "请求里没背包"只说一次,别把每一轮都刷满。 */
    private boolean inventoryMissingLogged;

    /**
     * 她此刻带着什么。服务端在背包真变化时推一份过来({@code CompanionStateWatch}),
     * 这里只负责渲染——所以"换没换"只有一个信号:快照的时间戳。
     *
     * <p>放进请求而不是让她调 {@code get_self_status},省的是<b>一整轮</b>(请求 + 工具结果 +
     * 再请求)。合并同类计数,不报耐久附魔:要精确到槽位时她该调 {@code inspect_gui}。
     */
    private String inventoryXml() {
        var snapshot = ClientNumenState.get(entityUuid).orElse(null);
        if (snapshot == null || !snapshot.loaded()) {
            // 链路断在客户端这一节:服务端没推过,或者推的是别的同伴。请求里就没有背包这回事,
            // 她只能靠对话历史猜——这条日志的存在就是为了不用再靠猜去查它。只在进入这个
            // 状态时说一次,别把每一轮都刷满。
            if (!inventoryMissingLogged) {
                inventoryMissingLogged = true;
                Constants.LOG.info("[numen-inv] {} 请求里没有背包块({})", entityUuid,
                        snapshot == null ? "客户端一份快照都没收到" : "身体未加载");
            }
            return "";
        }
        inventoryMissingLogged = false;
        if (snapshot == inventoryRenderedFrom) return inventoryRendered;
        inventoryRendered = renderInventory(snapshot);
        inventoryRenderedFrom = snapshot;
        // 这行只在快照真换了新的时才打,所以"年龄"读的是"这段时间背包没变过",不是延迟。
        // 背包明明变了却不见这一行,才是链路断了。
        Constants.LOG.info("[numen-inv] 背包块进请求:{} 字符,这份快照 {}ms 前收到",
                inventoryRendered.length(), System.currentTimeMillis() - snapshot.receivedAtMs());
        return inventoryRendered;
    }

    /**
     * 她身上这一刻在生效的东西。<b>只能现挂,不能进历史</b> —— 它带倒计时,沉进对话历史
     * 之后十轮再读到的不只是过时,是一个理直气壮的错秒数。
     *
     * <p>没有效果就一个字都不发:空块也是要读的 token,而"没写"和"写了没有"对模型是一样的。
     */
    private String effectsXml() {
        var snapshot = ClientNumenState.get(entityUuid).orElse(null);
        if (snapshot == null || !snapshot.loaded() || snapshot.effects().isEmpty()) {
            return "";
        }
        return "<effects>" + renderEffects(snapshot, System.currentTimeMillis()) + "</effects>";
    }

    /**
     * 她这一刻骑没骑着东西。与效果同一纪律:<b>只能现挂,不能进历史</b>——上下船是
     * 随时翻转的身体事实,沉进历史就成了理直气壮的错。没骑就一个字都不发。
     * 有这一行,模型不会再对自己坐着的船发第二次 interact_entity,也知道 goto
     * 会驾着它走、任何要走路的动作都会自己下来。
     */
    private String ridingXml() {
        var snapshot = ClientNumenState.get(entityUuid).orElse(null);
        if (snapshot == null || !snapshot.loaded() || snapshot.vehicleId() < 0) {
            return "";
        }
        return "<riding>" + xml(snapshot.vehicleType()) + " (entity id " + snapshot.vehicleId()
                + "). goto pilots a boat over water toward the target; any action that needs "
                + "walking steps off by itself — no need to click the vehicle again.</riding>";
    }

    /**
     * 身体状态片段(打头的 {@code <worn>} 与插件从身体上读的)——服务端在身体变化检查时拼好、随状态包推来的整段,这里原样挂上。
     * 和背包同一条路,所以她走远了、换了维度也在。
     */
    private String bodyStateXml() {
        var snapshot = ClientNumenState.get(entityUuid).orElse(null);
        if (snapshot == null || !snapshot.loaded()) {
            return "";
        }
        return snapshot.bodyState();
    }

    static String renderEffects(ClientNumenState.Snapshot snapshot, long nowMs) {
        StringBuilder out = new StringBuilder();
        for (var effect : snapshot.effects()) {
            int left = snapshot.remainingTicks(effect, nowMs);
            if (left == 0) {
                continue;   // 收到之后已经走完了
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(effect.getEffect().unwrapKey()
                    .map(key -> key.location().getPath()).orElse("unknown"));
            if (effect.getAmplifier() > 0) {
                out.append(" ").append(effect.getAmplifier() + 1);   // 原版 UI 的口径:0 级显示 I
            }
            out.append(left < 0 ? " (infinite)" : " (" + (left / 20) + "s left)");
        }
        return out.toString();
    }

    static String renderInventory(ClientNumenState.Snapshot snapshot) {
        java.util.Map<String, Integer> totals = new java.util.TreeMap<>();
        for (net.minecraft.world.item.ItemStack stack : snapshot.items()) {
            if (!stack.isEmpty()) {
                totals.merge(itemId(stack), stack.getCount(), Integer::sum);
            }
        }
        StringBuilder items = new StringBuilder();
        totals.forEach((id, count) -> {
            if (items.length() > 0) items.append(", ");
            items.append(id).append(" x").append(count);
        });
        // 手上那份不带数量,是刻意的:它本来就是 carrying 里的一堆,写上数量她会当成另一堆
        // 加起来(实测她把主手 64 个熔炉和清单里同一批数成了 128)。总数只有一处,手只指
        // 向它,结构上就没什么可重复计的。
        return "<inventory>Everything your body carries right now, totalled across all 36 backpack "
                + "slots — trust it and do not spend a call on get_self_status to rediscover it. "
                + "Call inspect_gui only when exact slots matter. A newer tool result wins over this."
                + "\ncarrying=" + (items.length() == 0 ? "nothing" : items)
                + "\nholding (already counted above)=main " + describe(snapshot.mainHand())
                + ", off " + describe(snapshot.offhand())
                + "</inventory>";
    }

    /** 手上拿的<b>是什么</b>,不含数量——数量归 {@code carrying} 一处管。 */
    private static String describe(net.minecraft.world.item.ItemStack stack) {
        return stack.isEmpty() ? "(empty)" : xml(itemId(stack));
    }

    private static String itemId(net.minecraft.world.item.ItemStack stack) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        String brew = brewLabel(stack);
        return brew.isEmpty() ? id : id + "[" + brew + "]";
    }

    /**
     * 瓶子里装的是什么。<b>治疗、剧毒、夜视的 item id 全都是 {@code minecraft:potion}</b> ——
     * 内容在 {@code POTION_CONTENTS} 组件里,只印 id 的话她背包里三瓶完全不同的东西长得
     * 一模一样,选不出该喝哪瓶。药箭同理。
     *
     * <p>印的是原版药水的<b>注册名</b>({@code strong_healing}、{@code long_poison}),不是
     * "安全/危险"那种结论 —— 该不该喝是她的判断,身体只负责说清楚这是什么。喷溅型和滞留型
     * 本来就是另外的 item id,照实印就分开了,不用另写判据。
     */
    private static String brewLabel(net.minecraft.world.item.ItemStack stack) {
        var contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return "";
        }
        StringBuilder label = new StringBuilder();
        contents.potion().ifPresent(held -> label.append(held.unwrapKey()
                .map(key -> key.location().getPath()).orElse("unknown")));
        // 酿造出来的、模组的药水没有预设名,效果只在自定义列表里 —— 两处都读,不用维护白名单。
        for (var effect : contents.customEffects()) {
            if (label.length() > 0) {
                label.append('+');
            }
            label.append(effect.getEffect().unwrapKey()
                    .map(key -> key.location().getPath()).orElse("unknown"));
        }
        return label.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String xml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
