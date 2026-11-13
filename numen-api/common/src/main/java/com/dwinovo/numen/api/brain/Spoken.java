package com.dwinovo.numen.api.brain;

import java.util.UUID;

/**
 * 主人的一句话是谁、在哪儿说的。她的循环记着最后一句的这两样({@link CompanionLoop#speaker}、{@link CompanionLoop#venue}):
 * 回话回给那一位、回到他问的地方({@link Voice}),她此刻在哪个会话里到他的会话表里解析,"眼前的主人"也是他。
 * 会话态,不落盘。
 *
 * @param who   说话的那位主人;{@code null} = 没有哪一位亲口说(主人定的定时到点、不经会话的输入),按创建者办
 * @param venue 他是在哪儿说的
 */
public record Spoken(UUID who, Venue venue) {

    /** 没有哪一位亲口说的话:按创建者办,回在面板。 */
    public static final Spoken NOBODY = new Spoken(null, Venue.PANEL);
}
