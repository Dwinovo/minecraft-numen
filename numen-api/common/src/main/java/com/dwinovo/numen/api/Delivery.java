package com.dwinovo.numen.api;

/**
 * 一句话交给同伴之后,调用方能知道的下场。大脑在主人的客户端,输入交给她的大脑就是全部:是排上了还是被压着,
 * 她自己判,不回给发送的一端。
 */
public enum Delivery {
    /** 空消息 / 她的大脑不在这台电脑上 —— 没送出去。 */
    REJECTED,
    /** 交给她的大脑了 —— 结果这边观察不到。 */
    HANDED_OFF
}
