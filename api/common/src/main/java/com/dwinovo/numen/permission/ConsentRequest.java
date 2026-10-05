package com.dwinovo.numen.permission;

import java.util.List;
import java.util.UUID;

/**
 * 一次征询:同伴要做的几件需要主人点头的事。清单本身就说清了要做什么,不另附原因。
 *
 * @param id                登记处发的号,答复带着它回来
 * @param companion         发起的同伴
 * @param items             清单
 * @param expiresAtGameTime 到这一刻还没答复就按拒绝
 */
public record ConsentRequest(long id, UUID companion, List<ConsentItem> items, long expiresAtGameTime) {

    public ConsentRequest {
        items = List.copyOf(items);
    }
}
