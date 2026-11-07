package com.dwinovo.numen.api;

/**
 * 一句话交给同伴之后,调用方能知道的下场。大脑在服务端,输入交给服务端就是全部:它是排上了、被压着,还是给了外接大脑,
 * 服务端自己判,不回给发送的一端。
 */
public enum Delivery {
    /** 空消息 / 不认识这个 UUID —— 没送出去。 */
    REJECTED,
    /** 交给服务端了 —— 结果这边观察不到。 */
    HANDED_OFF
}
