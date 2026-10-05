package com.dwinovo.numen.agent.script;

import java.util.Locale;

/**
 * 一次失败是哪一类:API 调用抛给脚本的错误值的 {@code kind}、回执数据里 {@code error.kind}。脚本 {@code pcall} 接住之后按它分支
 * ({@code if err.kind == "out_of_reach" then … end}),所以种类少而稳定,名字只在这里。
 *
 * <p>前几种是 API 调用的失败(脚本接得住);{@link #SYNTAX}、{@link #RUNTIME}、{@link #LIMIT} 只出现在整段程序的回执里:读不通、
 * 程序自己的运行错、到了上限。
 */
public enum ErrorKind {

    /** 调用写错了:参数读不成、缺了必填的、选项名不对、值的形状不对、传了 nil。 */
    BAD_ARGUMENT,
    /** 没有这个 API 函数。 */
    NO_FUNCTION,
    /** 点名的东西不在:区域、路线、设计、脚本、实体、方块不存在或已经没了。 */
    NOT_FOUND,
    /** 站在原地手够不着:先走过去。 */
    OUT_OF_REACH,
    /** 找不到去那儿的路,或走到一半走不下去了。 */
    NO_PATH,
    /** 身上缺要用掉的东西(要放的方块、要吃的食物、要交的物品)。 */
    NO_MATERIAL,
    /** 这件事要主人点头,而这一次没有问到。 */
    NEEDS_CONSENT,
    /** 主人拒绝,或规则不许。 */
    DENIED,
    /** 被叫停了:主人按停止、被顶替、身体离开世界。 */
    INTERRUPTED,
    /** 活到了它的期限还没做完;或主人的客户端在时限内没答复一次客户端函数调用(只读,原样再调一次是安全的)。 */
    TIMEOUT,
    /** 做了,没做成;别的种类都说不上的。 */
    FAILED,
    /** 程序读不通(语法错)。只在回执里。 */
    SYNTAX,
    /** 程序自己的运行错(索引了 nil、算错了类型、它自己 error 的一句话)。只在回执里。 */
    RUNTIME,
    /** 到了一段程序的上限(指令、字符串、调用栈)。只在回执里。 */
    LIMIT;

    /** 线上与脚本里的写法:{@code out_of_reach}。 */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
