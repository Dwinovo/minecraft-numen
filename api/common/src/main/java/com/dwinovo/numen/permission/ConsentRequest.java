package com.dwinovo.numen.permission;

import java.util.List;
import java.util.UUID;

/**
 * 一次征询:同伴要做的几件需要主人点头的事,和她为什么要做。
 *
 * @param id                登记处发的号,答复带着它回来
 * @param companion         发起的同伴
 * @param items             清单
 * @param reason            发起者写的原因(给主人看的那一句,如任务的人话描述)
 * @param expiresAtGameTime 到这一刻还没答复就按拒绝
 */
public record ConsentRequest(long id, UUID companion, List<ConsentItem> items, String reason,
                             long expiresAtGameTime) {

    public ConsentRequest {
        items = List.copyOf(items);
        reason = reason == null ? "" : reason;
    }
}
