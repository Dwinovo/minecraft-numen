package com.dwinovo.numen.agent.inbox;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 队列条目的类型表——{@link EventQueue} 里"某一类东西该怎么处理"的全部答案。
 *
 * <h2>为什么是表而不是 if</h2>
 * 队列本身不认识 {@code query} 和 {@code task_finished},更不该认识第三方内容包将来登记的
 * 类型。拼给模型的字符串、给聊天流的字符串、主人打断时清不清、是不是恒为急件——
 * 全都<b>查表</b>;持有队列的循环要知道"这条什么时候交给大脑""是不是主人说的",
 * 也查表。于是"主人打断清指令、留事实"这条规矩从代码里的判断变成了
 * 表里的一行,加一种新类型也不用回来改队列和循环。
 *
 * <h2>事件的种类就是一行</h2>
 * 主人的话、目标续跑、整理与清空是一行一种;世界上发生的事同样一种一行({@code task_finished}、
 * {@code death}、{@code reflex}……),条目的 {@code type} 就是它的种类,拼给模型的
 * {@code <event kind="…">} 里的 kind 取自同一个 id。世界的事的行都由 {@link #event} 造:
 * 它们只在"恒不恒急"上有分别。
 *
 * <h2>注册时机</h2>
 * 内置的在本类静态块里自注册(保证永远在);第三方在自己的 mod init 注册,
 * 与 {@code ToolRegistry} / {@code BrainChains} 同一约定 —— 注册在 init,
 * 读在运行时,没有并发窗口。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class EventTypes {

    /** 主人(或替主人说话的桥接)说的话。恒为急件:人说话了就该有回应。 */
    public static final String QUERY = "query";
    /**
     * 主人要求整理记忆。
     *
     * <p>它<b>不是拼给模型的文本</b>——{@code toModel} 返回 null,{@link EventQueue#drain}
     * 因此跳过它。队列只负责把它攒着、到点交出来;真去整理是取件那一方的事。
     *
     * <p>进队列而不是当场执行,是因为她忙的时候也该按得下:按了就一定会发生,
     * 主人不必盯着什么时候能按。
     */
    public static final String COMPACT = "compact";
    /**
     * 主人要求清空上下文。
     *
     * <p>与 {@link #COMPACT} 同一形状:不拼给模型({@code toModel} 回 null),进队列等安全点,
     * 真去清是取件那一方的事。区别只在取到之后干什么——整理发一次摘要请求,清空同步完事。
     */
    public static final String CLEAR = "clear";
    /**
     * 长期目标的续跑块。
     *
     * <p>是文本(要拼给模型),但不进聊天流——那一大块 steering 给主人看没有意义,目标本身
     * 在界面上另有一行。主人按停止就作废:那正是"我不要她接着跑了"。
     */
    public static final String GOAL = "goal";

    // ---- 世界上发生的事,一种一行 ----

    /** 异步任务收尾(status: done / failed / timeout / stopped / interrupted)。急不急由发送方按 status 定。 */
    public static final String TASK_FINISHED = "task_finished";
    /** 她死了又复活了(在客户端合成——身体那会儿已经不在了)。恒为急件:她对自己处境的认知几乎全作废了。 */
    public static final String DEATH = "death";
    /** 饿了 —— 她不会自己吃,得主人给或者叫她去弄。恒为急件。 */
    public static final String HUNGRY = "hungry";
    /** 主人挨打了(只报实体攻击)。急不急由发送方按主人血线分档。 */
    public static final String OWNER_HURT = "owner_hurt";
    /** 她自己定的表到点了。恒为急件:提醒而已,不代表那件事完成了。 */
    public static final String TIMER = "timer";
    /** 她从床上醒了。恒为急件:{@code sleep} 到躺下就返回,醒来这一刻只有这条事件说得出。 */
    public static final String WOKE = "woke";
    /** 同伴自己跨了维度。急不急由发送方定。 */
    public static final String DIMENSION_CHANGE = "dimension_change";
    /** 某个本能替身体做了一件事(溺水自救、下落放水、防御……),条目里带着是哪个本能。急不急由发送方定。 */
    public static final String REFLEX = "reflex";
    /** 队列满了丢掉了几条——丢弃可以,无声消失不行。 */
    public static final String DROPPED = "dropped";
    /**
     * 主人答应她的征询时附了一句话(拒绝的附言随任务收场送达,不走这里)。恒为急件:主人说话了,
     * 而她手上的活正照着那句答应接着干。
     */
    public static final String CONSENT_NOTE = "consent_note";

    /** 一类条目什么时候交给大脑。 */
    public enum Delivery {
        /** 插话:回合进行中在下一个边界(这批工具结算后、下次调模型前)注入;闲时参与熟度判断。 */
        STEER,
        /** 接续:回合进行中只在本来要停时接上;闲时同样参与熟度判断。 */
        FOLLOW_UP,
        /** 控制命令:不是给模型的文本,闲时由循环自己执行(整理记忆、清空上下文)。 */
        CONTROL
    }

    /**
     * 一种条目的处理方式。
     *
     * @param id                 类型 id,落盘时写在条目里
     * @param toModel            拼进 user 消息的字符串
     * @param chatPreview        进聊天流的样子;{@code null} = 这类东西不进聊天流
     * @param clearedByInterrupt 主人按停止时清不清 —— 清的是被取代的<em>指令</em>,
     *                           不清<em>事实</em>
     * @param fromOwner          是主人说的话,还是世界发生的事。决定排版:世界的事
     *                           归进 {@code <events>} 按时间排,主人的话一律垫底 ——
     *                           模型读到的顺序是"先看清发生了什么,再看主人要什么"
     * @param delivery           什么时候交给大脑,见 {@link Delivery}
     * @param alwaysUrgent       {@code true} = 这类恒为急件,发送方怎么标都一样;{@code false} =
     *                           急不急由发送方在 push 时定。生效规则只在 {@link EventQueue#push} 一处
     */
    public record Type(String id,
                       Function<String, String> toModel,
                       Function<String, String> chatPreview,
                       boolean clearedByInterrupt,
                       boolean fromOwner,
                       Delivery delivery,
                       boolean alwaysUrgent) {}

    private static final Map<String, Type> TYPES = new HashMap<>();

    /**
     * 世界上发生的一种事的那一行:插话投递、原文拼给模型、不进聊天流、打断不清(那是事实)、
     * 不是主人说的。几种事之间只差"恒不恒急"。
     *
     * @param alwaysUrgent {@code true} = 这种事恒为急件;{@code false} = 发送方定
     */
    public static Type event(String id, boolean alwaysUrgent) {
        return new Type(id, s -> s, s -> null, false, false, Delivery.STEER, alwaysUrgent);
    }

    /**
     * 这一侧没登记过的类型怎么处理:当作一件世界上发生的事,急不急听条目上的标记。
     *
     * <p>类型表是每个 JVM 各一份,条目却会跨过去:服务端的插件登记了、主人客户端没装的类型随包
     * 送来,或者读回来的旧条目的类型已经不在表里。这类条目都是别处发来的事实,所以按
     * {@link #event} 的那一行处理——原样交给模型,不因为不认识就消失。
     */
    static final Type UNKNOWN = event("?", false);

    static {
        // chatPreview 只回答"这类进不进聊天流",长什么样归渲染那一层——沙漏是 ChatView 加的。
        // 主人的话恒为急件:人说话了就该有回应。
        register(new Type(QUERY, s -> s, s -> s, true, true, Delivery.STEER, true));
        // 不进模型文本(toModel 回 null),但进聊天流——主人得看见自己按的整理排着。
        // 主人明确要求的事恒为急件,不跟世界事件一起攒着等阈值。
        register(new Type(COMPACT, s -> null, s -> s, true, true, Delivery.CONTROL, true));
        register(new Type(CLEAR, s -> null, s -> s, true, true, Delivery.CONTROL, true));
        // 续跑是评估器判过"还没做完"之后推的,这一推本身就是要她接着干。
        register(new Type(GOAL, s -> s, s -> null, true, true, Delivery.FOLLOW_UP, true));
        // 世界的事:恒急的几种是"她不知道,正在做的事就是错的",与她当时在干什么无关;
        // 其余由发的那一方按事情本身定(任务按 status、主人挨打按血线)。
        register(event(TASK_FINISHED, false));
        register(event(DEATH, true));
        register(event(HUNGRY, true));
        register(event(OWNER_HURT, false));
        register(event(TIMER, true));
        register(event(WOKE, true));
        register(event(DIMENSION_CHANGE, false));
        register(event(REFLEX, false));
        register(event(DROPPED, false));
        register(event(CONSENT_NOTE, true));
    }

    private EventTypes() {}

    /**
     * 登记一种类型(mod init 期调用)。一个 id 只能登记一次:内置的行是引擎语义的一部分(主人的话恒为急件、
     * 控制命令只在闲时执行),插件拿同一个 id 再登记一行就会悄悄改掉它们,所以直接拒绝。
     *
     * @throws IllegalArgumentException id 为空,或者这个 id 已经登记过
     */
    public static synchronized void register(Type type) {
        if (type == null || type.id() == null || type.id().isBlank()) {
            throw new IllegalArgumentException("类型没有 id");
        }
        if (TYPES.containsKey(type.id())) {
            throw new IllegalArgumentException("事件类型 " + type.id() + " 已经登记过,换一个 id");
        }
        TYPES.put(type.id(), type);
    }

    /** 查表;没登记过返回 {@link #UNKNOWN}。 */
    public static synchronized Type get(String id) {
        Type t = TYPES.get(id);
        return t == null ? UNKNOWN : t;
    }

    /** 这一侧登记过这种类型没有。发出一条事件的那一方用它挡住没登记的类型。 */
    public static synchronized boolean isRegistered(String id) {
        return TYPES.containsKey(id);
    }
}
