package com.dwinovo.numen.agent.inbox;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 队列条目的类型表——{@link EventQueue} 里"某一类东西该怎么处理"的全部答案。
 *
 * <h2>为什么是表而不是 if</h2>
 * 队列本身不认识 {@code query} 和 {@code event},更不该认识第三方内容包将来注册的
 * 类型。拼给模型的字符串、给聊天流的字符串、主人打断时清不清、是不是恒为急件——
 * 全都<b>查表</b>;持有队列的循环要知道"这条什么时候交给大脑""是不是主人说的",
 * 也查表。于是"主人打断清指令、留事实"这条规矩从代码里的判断变成了
 * 表里的一行,加一种新类型也不用回来改队列和循环。
 *
 * <h2>注册时机</h2>
 * 内置的两种在本类静态块里自注册(保证永远在);第三方在自己的 mod init 注册,
 * 与 {@code ToolRegistry} / {@code BrainChains} 同一约定 —— 注册在 init,
 * 读在运行时,没有并发窗口。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class EventTypes {

    /** 主人(或替主人说话的桥接)说的话。恒为急件:人说话了就该有回应。 */
    public static final String QUERY = "query";
    /** 世界发生的事。急不急由发的人在 push 时定。 */
    public static final String EVENT = "event";
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
     * 没登记过的类型的兜底:原样拼给模型、不进聊天流、不被打断清掉。
     *
     * <p>不是为了防模组卸载(类型跟着模组走),是防"注册漏了"这种自己人的失误——
     * 没有兜底的话表现是静默丢数据,那比多一行日志难查得多。
     */
    static final Type UNKNOWN = new Type("?", s -> s, s -> null, false, false, Delivery.STEER, false);

    static {
        // chatPreview 只回答"这类进不进聊天流",长什么样归渲染那一层——沙漏是 ChatView 加的。
        // 主人的话恒为急件:人说话了就该有回应。
        register(new Type(QUERY, s -> s, s -> s, true, true, Delivery.STEER, true));
        // 世界的事没有资格自定紧急度,急不急由发事件的那一方按事情本身定。
        register(new Type(EVENT, s -> s, s -> null, false, false, Delivery.STEER, false));
        // 不进模型文本(toModel 回 null),但进聊天流——主人得看见自己按的整理排着。
        // 主人明确要求的事恒为急件,不跟世界事件一起攒着等阈值。
        register(new Type(COMPACT, s -> null, s -> s, true, true, Delivery.CONTROL, true));
        register(new Type(CLEAR, s -> null, s -> s, true, true, Delivery.CONTROL, true));
        // 续跑是评估器判过"还没做完"之后推的,这一推本身就是要她接着干。
        register(new Type(GOAL, s -> s, s -> null, true, true, Delivery.FOLLOW_UP, true));
    }

    private EventTypes() {}

    /** 登记一种类型(mod init 期调用)。同 id 重复登记以后来的为准。 */
    public static synchronized void register(Type type) {
        if (type != null && type.id() != null && !type.id().isBlank()) {
            TYPES.put(type.id(), type);
        }
    }

    /** 查表;没登记过返回 {@link #UNKNOWN}。 */
    public static synchronized Type get(String id) {
        Type t = TYPES.get(id);
        return t == null ? UNKNOWN : t;
    }
}
