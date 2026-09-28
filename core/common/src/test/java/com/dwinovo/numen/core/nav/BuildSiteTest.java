package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 走向工地外圈的那条路怎么对待图纸格:不挖、不往里放,站上去、穿过去贵得像绕一百格路,工地外的格照旧。 */
class BuildSiteTest {

    private static final BlockPos SITE = new BlockPos(10, 64, 10);
    private static final BlockPos OUTSIDE = new BlockPos(20, 64, 20);

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void siteCellsAreNeverDugOrFilledAndCostDearlyToStandOnOrPassThrough() {
        RouteSpec base = RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build();
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(SITE.asLong());
        PositionCosts site = BuildSite.around(base, cells).positions();

        assertTrue(site.forbids(Use.DIG, SITE.asLong()), "工地格不许挖");
        assertTrue(site.forbids(Use.PLACE, SITE.asLong()), "工地格不许往里放");
        assertFalse(site.forbids(Use.STAND, SITE.asLong()), "被挤进工地里还得走得出来:站上去不是禁令");
        assertFalse(site.forbids(Use.PASS, SITE.asLong()));
        double walkHundred = site.extra(Use.STAND, SITE.asLong());
        assertTrue(walkHundred > 0 && walkHundred == site.extra(Use.PASS, SITE.asLong()), "站上与穿过同样加价");

        assertFalse(site.forbids(Use.DIG, OUTSIDE.asLong()), "工地外照旧");
        assertEquals(0, site.extra(Use.STAND, OUTSIDE.asLong()));
    }

    @Test
    void theRestOfTheSpecIsKept() {
        RouteSpec base = RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).takeBack(true).build();
        RouteSpec around = BuildSite.around(base, new LongOpenHashSet(new long[] {SITE.asLong()}));
        assertEquals(RouteSpec.Alter.NATURAL, around.alter());
        assertTrue(around.takeBack());
    }
}
