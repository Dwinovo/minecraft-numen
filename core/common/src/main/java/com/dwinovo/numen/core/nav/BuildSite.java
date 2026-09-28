package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.pathing.plan.ActionCosts;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * 建造工地的位置代价:走出工地、走向外圈的那条路按位置避开图纸格。图纸格不挖、不往里放东西,是硬禁;站上去或穿过去是重价
 * ——被外力挪进工地里时她还得走得出来,走出来那几步就是她付的这份价。
 */
public final class BuildSite {

    /**
     * 踩进或穿过工地格的代价:一步抵一百格路。任何绕行都比穿过去便宜,所以走向外圈永远绕着图纸走;但它不是禁令——开工时站在
     * 工地里、被挤、被推、掉进图纸里时,她还得能走出来。
     */
    private static final double BODY_COST = ActionCosts.WALK_ONE_BLOCK * 100;

    private BuildSite() {}

    /** {@code base} 并上工地格 {@code cells} 的三条:禁挖、禁放、站上与穿过加价。 */
    public static RouteSpec around(RouteSpec base, LongSet cells) {
        PositionCosts.Builder body = PositionCosts.builder();
        cells.forEach((long cell) -> body.add(Use.STAND, cell, BODY_COST).add(Use.PASS, cell, BODY_COST));
        PositionCosts pins = PositionCosts.protect(cells).plus(body.build());
        return base.edit().positions(base.positions().plus(pins)).build();
    }
}
