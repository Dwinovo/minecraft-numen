package com.dwinovo.numen.api;

import com.dwinovo.numen.api.entity.NumenPlayer;

import java.util.UUID;

/**
 * 同伴身上会发生的事,插件用 {@code numen.on(事件, 处理器)} 订阅。
 *
 * <h2>为什么是常量而不是一个方法一个事件</h2>
 * 一个事件一个静态方法({@code onSpawn} / {@code onDeath} / …)的话,<b>每加一个事件
 * 就得改一次 API 的形状</b>——加方法、发版、消费者升级。而这里加事件只是多一个常量,
 * 订阅那一头 {@code on(…)} 一个字都不用动。
 *
 * <p>泛型参数是处理器收到的东西。事件名只用于日志与排错,订阅时用的是常量本身,
 * 拼错了编译期就不过。
 */
public final class CompanionEvent<T> {

    /**
     * 身体刚进世界(召唤、从休眠唤醒,或死后经原版重生换上来的新身体),已经在玩家列表里、可以对它下命令了。
     * 模组上重生出来的是新的身体对象:按身体对象记的东西在 {@link #REMOVE} 时收掉,在这里为新身体重建。
     */
    public static final CompanionEvent<NumenPlayer> SPAWN = new CompanionEvent<>("companion_spawn");

    /** 身体正在离开世界(休眠 / 注销 / 被请出服务器 / 死后重生之前)。此刻它还在,之后就没了(重生的话,换上来的是另一具)。 */
    public static final CompanionEvent<NumenPlayer> REMOVE = new CompanionEvent<>("companion_remove");

    /** 身体刚死,原版的死亡流程已经走完,还没重生(重生在下一个服务端刻开头)。 */
    public static final CompanionEvent<NumenPlayer> DEATH = new CompanionEvent<>("companion_death");

    /** 主人打断了这个同伴的思考——手上没做完的活该放弃了。给的是 UUID,因为身体未必还在。 */
    public static final CompanionEvent<UUID> ABORT = new CompanionEvent<>("companion_abort");

    /** 她挨了一下。伤害已经结算,{@code amount} 是<b>结算前</b>报上来的量。 */
    public static final CompanionEvent<Hurt> HURT = new CompanionEvent<>("companion_hurt");

    /**
     * {@link #HURT} 的内容。
     *
     * <p>带上来源和伤害量,而不是只给身体:监听者要据此<b>分情况</b>——护盾看是谁打的,
     * 音效看轻重,提醒主人看急不急。只给身体的话每个监听者都得自己去
     * {@code getLastDamageSource()} 捞一遍,而那时它可能已经被下一次伤害盖掉了。
     */
    public record Hurt(NumenPlayer companion,
                       net.minecraft.world.damagesource.DamageSource source,
                       float amount) {}

    /**
     * 她的大脑开了一次 run(一次连续的想与做:从被叫醒到没有要接着处理的事)。
     *
     * <p>{@link #RUN_ENDED} 与它成对:一次 run 先 started 后 ended,按发生顺序。外部大脑驾驶时没有 run,这两个事件不发。
     * 派发的端、线程、出错规则见 {@link RunStarted}。
     */
    public static final CompanionEvent<RunStarted> RUN_STARTED = new CompanionEvent<>("run_started");

    /** 一次 run 收尾了,{@link RunEnded#outcome} 说它怎么收的。外部大脑驾驶时不发。 */
    public static final CompanionEvent<RunEnded> RUN_ENDED = new CompanionEvent<>("run_ended");

    /**
     * 她派出去的一件活收尾了(完成、失败、超时、被叫停、被打断),不论这条收尾归了在跑的程序还是进了她的收件箱,都发<b>一次</b>。
     * 外部大脑驾驶时照发。载荷见 {@link TaskFinished}。
     */
    public static final CompanionEvent<TaskFinished> TASK_FINISHED = new CompanionEvent<>("task_finished");

    /**
     * {@link #RUN_STARTED} 的内容。大脑事件({@link #RUN_STARTED}、{@link #RUN_ENDED})的共同约定:
     * <ul>
     *   <li>只读通知:不能改写、拦截她的输入输出;要限制她做什么,登记权限的对象组;</li>
     *   <li>在服务端,服务端主线程上,每个服务器刻一次、在大脑内核两步之间统一派发——回调里调 {@code NumenApi}
     *       的门是安全的;按发生的先后送,同一刻内的多个事件挨个送;</li>
     *   <li>订阅者逐个隔离:一个抛了只记日志,其余照收,不影响她的大脑;</li>
     *   <li>她的大脑被收掉(遣散、换新)时,没派出的先派完再收;关服时不派。</li>
     * </ul>
     *
     * <p>载荷是只追加字段的 record,以后加字段不改已有的。给的是 UUID 而不是身体:run 的开收不依赖身体在不在世界里。
     *
     * @param companion 她的 UUID
     * @param runId     这一次 run 的编号,同一只同伴内递增;与 {@link RunEnded#runId} 配对
     */
    public record RunStarted(UUID companion, long runId) {}

    /**
     * {@link #RUN_ENDED} 的内容。派发约定见 {@link RunStarted}。
     *
     * @param companion 她的 UUID
     * @param runId     对应 {@link RunStarted#runId}
     * @param outcome   怎么收的
     * @param reason    {@link Outcome#DONE} 时是空串;{@link Outcome#FAILED} 时是模型调用没成的原因码(如 {@code llm.network}、
     *                  {@code llm.rate_limited});{@link Outcome#HALTED} 时是切断的原因码({@code halt.owner_stop}、
     *                  {@code halt.death}、{@code halt.removed}、{@code halt.disconnect}、{@code halt.external}、
     *                  {@code halt.dispose})
     */
    public record RunEnded(UUID companion, long runId, Outcome outcome, String reason) {}

    /** 一次 run 的结局。 */
    public enum Outcome {
        /** 模型说完了,没有要接着处理的。 */
        DONE,
        /** 模型调用失败。 */
        FAILED,
        /** 被切断(主人停止、死亡、登出、外接接管、遣散等)。 */
        HALTED
    }

    /**
     * {@link #TASK_FINISHED} 的内容。收尾那一刻、服务端主线程上同步派发(回调里不要去推进她的任务槽);订阅者逐个隔离,
     * 出错只记日志;载荷只追加字段。
     *
     * @param companion 她的身体
     * @param taskId    这件活的编号(她读到的 {@code task_finished} 事件里的 id)
     * @param task      这件活的名字(登记的任务名)
     * @param status    {@code done}、{@code failed}、{@code timeout}、{@code stopped}、{@code interrupted} 之一
     * @param result    她读到的那段结果文字
     */
    public record TaskFinished(NumenPlayer companion, String taskId, String task, String status, String result) {}

    private final String name;

    private CompanionEvent(String name) {
        this.name = name;
    }

    public String eventName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
