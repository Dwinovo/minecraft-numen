package com.dwinovo.numen.pathing.plan;

import java.util.List;
import java.util.Set;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.spec.BlockBans;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.spec.RouteSpec.Alter;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 成本模型:挖与放的准入按规格与许可分开,价钱只在一处算。 */
class CostModelTest {

    private static final BlockPos FREE = new BlockPos(0, 64, 0);
    private static final BlockPos ASK = new BlockPos(1, 64, 0);
    private static final BlockPos DENY = new BlockPos(2, 64, 0);
    private static BlockState STONE;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
        STONE = Blocks.STONE.defaultBlockState();
    }

    private static final TerrainPolicy POLICY = (change, pos, state, view) -> pos.equals(ASK) ? Permit.ask("主人的箱子")
            : pos.equals(DENY) ? Permit.deny("玩家放的") : Permit.ALLOW;

    private static CostModel model(Alter alter) {
        return CostModel.of(RouteSpec.defaults().edit().alter(alter).build(), Fixtures.body(), POLICY, Fixtures.COBBLE,
                Threats.NONE);
    }

    private static TestWorld stones() {
        return new TestWorld().set(FREE, STONE).set(ASK, STONE).set(DENY, STONE);
    }

    @Test
    void noneRefusesEverythingNaturalTakesWhatIsAllowedAndAnyAlsoWhatNeedsConsent() {
        TestWorld world = stones();
        assertEquals(Reason.NEEDS_ALTER, model(Alter.NONE).admitDig(world, FREE, STONE).refused());

        CostModel natural = model(Alter.NATURAL);
        assertInstanceOf(Permit.Allow.class, natural.admitDig(world, FREE, STONE).permit());
        assertEquals(Reason.NEEDS_CONSENT, natural.admitDig(world, ASK, STONE).refused());
        CostModel.Admission denied = natural.admitDig(world, DENY, STONE);
        assertEquals(Reason.DENIED, denied.refused());
        assertEquals("玩家放的", denied.detail(), "许可给的理由原样交还");

        CostModel any = model(Alter.ANY);
        assertEquals("主人的箱子", assertInstanceOf(Permit.Ask.class, any.admitDig(world, ASK, STONE).permit()).credential());
        assertEquals(Reason.DENIED, any.admitDig(world, DENY, STONE).refused(), "拒绝的格在 any 下也不进");
    }

    @Test
    void aCellThatNeedsConsentCostsMuchMore() {
        CostModel any = model(Alter.ANY);
        double free = any.digCost(new Edit.Dig(FREE, STONE, Permit.ALLOW, false, true));
        double asked = any.digCost(new Edit.Dig(ASK, STONE, Permit.ask("x"), false, true));
        assertEquals(free * ActionCosts.CONSENT_MULTIPLIER, asked, 1e-9);
    }

    @Test
    void placingPricesTakingTheBlockBackWhenTheSpecAsksForIt() {
        RouteSpec keep = RouteSpec.defaults().edit().alter(Alter.NATURAL).build();
        CostModel left = CostModel.of(keep, Fixtures.body(), TerrainPolicy.ALLOW_ALL, Fixtures.COBBLE, Threats.NONE);
        CostModel taken = left.withSpec(keep.edit().takeBack(true).build());
        Edit.Place place = new Edit.Place(FREE, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE, Permit.ALLOW);
        double pickUp = left.tools().handTicks(Blocks.COBBLESTONE.defaultBlockState(), false, true);
        assertEquals(left.placeCost(place) + pickUp, taken.placeCost(place), 1e-9);
    }

    @Test
    void positionsAndBansForbidDiggingAndPlacing() {
        TestWorld world = stones();
        RouteSpec spec = RouteSpec.defaults().edit().alter(Alter.NATURAL)
                .positions(PositionCosts.builder().forbid(Use.DIG, FREE.asLong()).build())
                .bans(new BlockBans(Set.of(), Set.of(Blocks.WATER), Set.of())).build();
        CostModel model = CostModel.of(spec, Fixtures.body(), TerrainPolicy.ALLOW_ALL, Fixtures.COBBLE, Threats.NONE);
        assertEquals(Reason.FORBIDDEN, model.admitDig(world, FREE, STONE).refused());
        BlockPos pond = new BlockPos(0, 60, 0);
        world.set(pond, Blocks.WATER.defaultBlockState()).set(pond.below(), STONE);
        assertEquals(Reason.FORBIDDEN, model.admitPlace(world, pond, Blocks.WATER.defaultBlockState()).refused());
    }

    /** 规格按位置禁放的一格,往里放垫路料不准入,原因是"规格禁止";同一格不禁时放得进去。 */
    @Test
    void placingIntoACellThePositionsForbidIsRefused() {
        TestWorld world = new TestWorld().set(FREE.below(), STONE);
        RouteSpec natural = RouteSpec.defaults().edit().alter(Alter.NATURAL).build();
        CostModel open = CostModel.of(natural, Fixtures.body(), TerrainPolicy.ALLOW_ALL, Fixtures.COBBLE, Threats.NONE);
        assertNull(open.admitPlace(world, FREE, Blocks.AIR.defaultBlockState()).refused());
        CostModel forbidden = open.withSpec(natural.edit()
                .positions(PositionCosts.builder().forbid(Use.PLACE, FREE.asLong()).build()).build());
        assertEquals(Reason.FORBIDDEN, forbidden.admitPlace(world, FREE, Blocks.AIR.defaultBlockState()).refused());
    }

    /**
     * 挖一格的价钱由三份合成:这具身体用挑中的工具挖到碎的刻数(与 {@link DigTime} 按同一份身体快照算的一致,眼睛泡没泡在
     * 水里、脚着没着地照样算进去),碎了之后缓手的 5 刻(原版的 {@code destroyDelay}),加上规格的挖掘罚分。
     */
    @Test
    void diggingCostsTheDigTimeWithTheChosenToolPlusTheBreakPenalty() {
        RouteSpec spec = RouteSpec.defaults().edit().alter(Alter.NATURAL).breakPenalty(7.5).build();
        BodySnapshot body = Fixtures.carrying(3, new ItemStack(Items.WOODEN_PICKAXE));
        CostModel model = CostModel.of(spec, body, TerrainPolicy.ALLOW_ALL, Materials.NONE, Threats.NONE);
        for (boolean eyeInWater : new boolean[] {false, true}) {
            for (boolean grounded : new boolean[] {true, false}) {
                double ticks = DigTime.ticks(body, new ItemStack(Items.WOODEN_PICKAXE), STONE, eyeInWater, grounded);
                assertEquals(ticks + 5 + 7.5, model.digCost(new Edit.Dig(FREE, STONE, Permit.ALLOW, eyeInWater, grounded)), 1e-9,
                        "水里 " + eyeInWater + ",着地 " + grounded);
            }
        }
    }

    /** 危险半径不同的两只生物,各自只在自己的半径里加价:离小的那只四格没事,离大的那只四格要加价。 */
    @Test
    void eachCreatureRaisesThePriceOnlyWithinItsOwnRadius() {
        Threat small = new Threat(0.5, 64, 0.5, 2);
        Threat wide = new Threat(20.5, 64, 0.5, 5);
        CostModel model = CostModel.of(RouteSpec.defaults(), Fixtures.body(), TerrainPolicy.ALLOW_ALL, Materials.NONE,
                () -> List.of(small, wide));
        double inside = model.extra(Use.PASS, new BlockPos(1, 64, 0).asLong());
        assertTrue(inside > 0, "小的那只半径里");
        assertEquals(0, model.extra(Use.PASS, new BlockPos(4, 64, 0).asLong()), "离小的那只四格,出了它的半径");
        assertEquals(inside, model.extra(Use.PASS, new BlockPos(24, 64, 0).asLong()), 1e-9, "离大的那只四格,还在它的半径里");
        assertEquals(0, model.extra(Use.PASS, new BlockPos(26, 64, 0).asLong()), "离大的那只五格半,出了它的半径");
    }

    @Test
    void creaturesMakeTheCellsAroundThemDearer() {
        List<Threat> zombie = List.of(new Threat(10.5, 64, 10.5, 2));
        CostModel model = CostModel.of(RouteSpec.defaults(), Fixtures.body(), TerrainPolicy.ALLOW_ALL, Materials.NONE,
                () -> zombie);
        assertEquals(ActionCosts.DANGER_PER_CELL, model.extra(Use.PASS, new BlockPos(11, 64, 10).asLong()), 1e-9);
        assertEquals(0, model.extra(Use.PASS, new BlockPos(20, 64, 10).asLong()));
    }

    @Test
    void theFallLimitIsTheBodysAndTheSpecOnlyTightensIt() {
        RouteSpec loose = RouteSpec.defaults().edit().maxFallHeightNoWater(100).build();
        CostModel healthy = CostModel.of(loose, Fixtures.body(20), TerrainPolicy.ALLOW_ALL, Materials.NONE, Threats.NONE);
        assertTrue(bears(healthy, 17), "20 点血摔完留 6 点:能摔 17 格");
        assertFalse(bears(healthy, 18));
        CostModel weak = CostModel.of(loose, Fixtures.body(6), TerrainPolicy.ALLOW_ALL, Materials.NONE, Threats.NONE);
        assertTrue(bears(weak, 3), "只剩 6 点血时只落摔不疼的高度");
        assertFalse(bears(weak, 4));
        CostModel factory = CostModel.of(RouteSpec.defaults(), Fixtures.body(20), TerrainPolicy.ALLOW_ALL, Materials.NONE,
                Threats.NONE);
        assertTrue(bears(factory, 3), "出厂规格收紧到 3");
        assertFalse(bears(factory, 4));
    }

    /** 从 {@code height} 格高处落到石头上摔不摔得起。 */
    private static boolean bears(CostModel model, int height) {
        return model.bearsFall(height, model.body().fallDamage(height, Blocks.STONE.defaultBlockState()));
    }
}
