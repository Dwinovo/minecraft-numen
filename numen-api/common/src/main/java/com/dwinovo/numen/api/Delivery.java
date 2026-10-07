package com.dwinovo.numen.api;

/** 一句话交给同伴之后的下场。 */
public enum Delivery {
    /** 空消息 / 不认识这个 UUID —— 没送出去。 */
    REJECTED,
    /** 她当场就看了(请求已经发出)。 */
    SEEN,
    /** 先排着:她手上有事没法马上看(在飞的回合、未决工具、死着、被停止)。 */
    QUEUED,
    /** 外接大脑在开车:话进了事件线,由它取走回话。内脑整体停牌。 */
    TO_EXTERNAL_BRAIN,
    /** 不在客户端主线程,只能交给它稍后处理 —— 结果这边观察不到。 */
    HANDED_OFF
}
