package com.dwinovo.numen.nav;

import java.util.List;

import com.dwinovo.numen.FailureType;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.pathing.drive.Blockage;
import com.dwinovo.numen.pathing.drive.DiveLog;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.Reason;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 寻路交出的事实怎么说给模型:路上真改了什么、身体做了什么、一条候选要动什么、结局归到哪一种失败。 */
class NavTextTest {

    private static final BlockPos A = new BlockPos(120, 64, -33);
    private static final BlockPos B = new BlockPos(120, 65, -33);
    private static final BlockPos C = new BlockPos(121, 64, -33);

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static BlockState planks() {
        return Blocks.OAK_PLANKS.defaultBlockState();
    }

    /** 挖掉一列 {@code n} 格木板,从 A 往上。 */
    private static List<Edit> digs(int n) {
        return digs(n, Permit.ALLOW);
    }

    private static List<Edit> digs(int n, Permit permit) {
        List<Edit> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Edit.Dig(A.above(i), planks(), permit, false, true));
        }
        return out;
    }

    @Test
    void theActualLedgerNamesEveryBlockAndCellAndWhatTheBodyDid() {
        List<EditLedger.Entry> entries = List.of(
                new EditLedger.Dug(A, planks()),
                new EditLedger.Dug(B, planks()),
                new EditLedger.Placed(C, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState()),
                new EditLedger.Toggled(C.above(), Blocks.OAK_DOOR.defaultBlockState(), Blocks.OAK_DOOR.defaultBlockState()));
        List<BodyAction> actions = List.of(new BodyAction.Held(Items.COBBLESTONE, 3, 3),
                new BodyAction.Held(Items.COBBLESTONE, 3, 3));
        assertEquals("En route I had to break 2 oak_planks (120,64,-33; 120,65,-33) and place 1 cobblestone"
                + " (121,64,-33). I also switched to cobblestone in my hotbar (2 times).",
                NavText.journey(entries, actions, List.of(), List.of()));
        assertEquals("", NavText.journey(List.of(), List.of(), List.of(), List.of()), "什么都没做就什么都不说");
        assertEquals("En route I took a stack of dirt from the creative inventory.",
                NavText.journey(List.of(), List.of(new BodyAction.Conjured(Items.DIRT, 0)), List.of(), List.of()), "只有身体动作也要说");
    }

    @Test
    void waterPouredToBreakAFallAndScoopedBackIsToldAsSuch() {
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        String said = NavText.journey(List.of(new EditLedger.Placed(C, air, water),
                new EditLedger.Placed(C, water, air)), List.of(), List.of(), List.of());
        assertTrue(said.contains("pour 1 water (121,64,-33) to break a fall")
                && said.contains("scoop 1 water (121,64,-33) back"), said);
    }

    /** 预算账与实际账同一种写法:要挖的按方块归堆,要问主人的缀上为什么问;要放的归堆;一格不动就说不动。 */
    @Test
    void aPlanListsItsBreaksWithWhyTheyNeedConsentAndItsPlaces() {
        java.util.Map<BlockPos, net.minecraft.world.level.block.Block> digs = new java.util.LinkedHashMap<>();
        digs.put(A, Blocks.OAK_PLANKS);
        digs.put(B, Blocks.OAK_PLANKS);
        digs.put(B.above(), Blocks.STONE);
        assertEquals("break 2 oak_planks (120,64,-33; 120,65,-33) needing consent (placed by a player) and 1 stone "
                        + "(120,66,-33); place 1 cobblestone (121,64,-33)",
                NavText.planned(digs, java.util.Map.of(C, Blocks.COBBLESTONE),
                        java.util.Map.of(A, "placed by a player", B, "placed by a player")));
        assertEquals("no terrain change", NavText.planned(java.util.Map.of(), java.util.Map.of(), java.util.Map.of()));
    }

    /**
     * 不改地形走不到、改了才有路(只有不改地形的活会这样收场):那条路要改的格照实点名,下一步是 numen.move.to;要问主人的格说清走到时才问。
     */
    @Test
    void notEnoughChangesNameTheCellsTheWayWouldChange() {
        RouteSpec none = RouteSpec.defaults();
        String digs = NavText.failure(new Outcome.NeedsChanges(digs(2)), null, A, C, none, List.of());
        assertTrue(digs.contains("`numen.move.to` takes such a way")
                && digs.contains("changes 2 block(s) — break 2 oak_planks (120,64,-33; 120,65,-33)"), digs);
        String asks = NavText.failure(new Outcome.NeedsChanges(digs(3, Permit.ask("x"))), null, A, C, none, List.of());
        assertTrue(asks.contains("asks your owner when I get to each"), asks);
        String budget = NavText.failure(new Outcome.OverAlterBudget(5), null, A, C,
                none.edit().alterBudget(1).build(), List.of());
        assertTrue(budget.contains("no route within a budget of 1 changed blocks"), budget);
    }

    /** 路上被拒的格照实说:改什么、在哪、谁怎么说的;答应了的不在这一句里。 */
    @Test
    void whatWasRefusedOnTheWayIsToldWithWhoSaidIt() {
        java.util.List<Ruling> rulings = java.util.List.of(
                Ruling.refusedByRules(A, "oak_planks", "placed by a player"),
                new Ruling(B, "break stone", true, "your owner"));
        assertEquals("Refused on the way: change oak_planks at 120,64,-33 (the rules: placed by a player).",
                NavText.journey(List.of(), List.of(), List.of(), rulings));
    }

    @Test
    void everyOutcomeMapsToOneFailureKind() {
        assertEquals(FailureType.NO_PATH, NavText.type(new Outcome.NoRoute(null)));
        assertEquals(FailureType.NO_PATH, NavText.type(new Outcome.OutOfBudget(null)));
        assertEquals(FailureType.NO_PATH, NavText.type(new Outcome.Unloaded()));
        assertEquals(FailureType.NO_PATH, NavText.type(new Outcome.OverAlterBudget(4)));
        assertEquals(FailureType.TERRAIN_BLOCKED, NavText.type(new Outcome.NeedsChanges(digs(2))));
        assertEquals(FailureType.NO_MATERIAL, NavText.type(new Outcome.NoMaterials(3)));
        assertEquals(FailureType.REFUSED, NavText.type(new Outcome.Denied(A, "no")));
        assertEquals(FailureType.BOXED_IN, NavText.type(new Outcome.Stranded(A, planks())));
        assertEquals(FailureType.OCCLUDED, NavText.type(new Outcome.NoLineOfSight(A)));
        Blockage stuck = new Blockage(A, planks(), MoveKind.ASCEND, Reason.TOO_HIGH, null);
        assertEquals(FailureType.BOXED_IN, NavText.type(new Outcome.Blocked(stuck)));
        assertEquals("stepping up at 120,64,-33 (oak_planks) kept failing: too high to step or jump up",
                NavText.blockage(stuck));
    }

    @Test
    void puttingAwayWhatWasInHandIsToldAsSuch() {
        assertEquals("En route I put away what was in my hand.",
                NavText.journey(List.of(), List.of(new BodyAction.Held(Items.AIR, 8, 8)), List.of(), List.of()));
    }

    /** 路上潜过的水照实说:几段,憋得最久的那一段从哪儿到哪儿、憋了几秒、氧气最低到多少;跟在改动与身体动作后面。 */
    @Test
    void theDivesOnTheWayAreToldWithTheLongestHold() {
        List<DiveLog.Dive> dives = List.of(new DiveLog.Dive(A, B, 30, 270, 300), new DiveLog.Dive(B, C, 245, 55, 300));
        assertEquals("I went under water 2 times; the longest: 12 s without a breath from 120,65,-33 to 121,64,-33,"
                + " air down to 55/300.", NavText.journey(List.of(), List.of(), dives, List.of()));
        String both = NavText.journey(List.of(new EditLedger.Dug(A, planks())), List.of(),
                List.of(new DiveLog.Dive(A, C, 10, 290, 300)), List.of());
        assertEquals("En route I had to break 1 oak_planks (120,64,-33). I went under water once: under 1 s without a"
                + " breath from 120,64,-33 to 121,64,-33, air down to 290/300.", both);
    }

    /** 憋不住气的结局:说出那一段水下在哪、要憋多久、此刻能安全憋多久,以及换成有水下呼吸就走得了;归到危险一类。 */
    @Test
    void aWayTooLongUnderWaterSaysWhereAndHowLong() {
        Outcome.Breathless breathless = new Outcome.Breathless(A, C, 500, 240);
        String said = NavText.failure(breathless, null, A, C, RouteSpec.defaults(), List.of());
        assertTrue(said.contains("swims under water from 120,64,-33 to 121,64,-33") && said.contains("about 25 s without a"
                + " breath") && said.contains("about 12 s") && said.contains("water breathing"), said);
        assertEquals(FailureType.HAZARD, NavText.type(breathless));
        assertEquals("swimming at 120,64,-33 (water) kept failing: with the air I have left I could not reach air before"
                + " drowning", NavText.blockage(new Blockage(A, Blocks.WATER.defaultBlockState(), MoveKind.SWIM,
                Reason.OUT_OF_BREATH, null)));
    }

    @Test
    void onlyASearchedOutWalkSaysEveryCellWasSearchedAndARunOutBudgetIsNoProof() {
        String none = NavText.failure(new Outcome.NoRoute(null), null, A, C, RouteSpec.defaults(), List.of());
        assertTrue(none.contains("every reachable cell was searched"), none);
        String budget = NavText.failure(new Outcome.OutOfBudget(null), null, A, C, RouteSpec.defaults(), List.of());
        assertTrue(budget.contains("not proof there is none") && !budget.contains("every reachable cell"), budget);
    }

    /** 每一种没走到各说各的原因与下一步,不并成同一句"到不了"。 */
    @Test
    void everyWayOfNotGettingThereSaysItsOwnReasonAndWhatToTryNext() {
        RouteSpec spec = RouteSpec.defaults();
        String none = NavText.failure(new Outcome.NoRoute(null), null, A, C, spec, List.of());
        String budget = NavText.failure(new Outcome.OutOfBudget(null), null, A, C, spec, List.of());
        String unloaded = NavText.failure(new Outcome.Unloaded(), null, A, C, spec, List.of());
        String alter = NavText.failure(new Outcome.NeedsChanges(digs(2)), null, A, C, spec, List.of());
        String denied = NavText.failure(new Outcome.Denied(B, "no"), null, A, C, spec, List.of());
        String stranded = NavText.failure(new Outcome.Stranded(A, planks()), null, A, C, spec, List.of());
        String blocked = NavText.failure(new Outcome.Blocked(new Blockage(B, planks(), MoveKind.WALK, Reason.NO_CLEARANCE,
                null)), null, A, C, spec, List.of());
        String sight = NavText.failure(new Outcome.NoLineOfSight(B), null, A, C, spec, List.of());
        assertTrue(none.contains("pick another destination"), none);
        assertTrue(budget.contains("not proof there is none") && budget.contains("a nearer place"), budget);
        assertTrue(unloaded.contains("not loaded") && unloaded.contains("walk toward it and try again"), unloaded);
        assertTrue(alter.contains("`numen.move.to` takes such a way"), alter);
        assertTrue(denied.contains("120,65,-33 is refused") && denied.contains("ask your owner")
                && denied.contains("no way around it was found"), denied);
        assertTrue(stranded.contains("can't stand where I am") && stranded.contains("free me first"), stranded);
        assertTrue(blocked.contains("no room for my body there") && blocked.contains("try again"), blocked);
        assertTrue(sight.contains("went out of sight") && sight.contains("numen.work.dig"), sight);
        assertEquals(8, java.util.Set.of(none, budget, unloaded, alter, denied, stranded, blocked, sight).size());
    }

    @Test
    void anOverBudgetWalkSaysWhatTheCheapestRouteWouldChange() {
        assertEquals("no route within a budget of 1 changed blocks (the cheapest found would change 3)",
                NavText.overBudget(1, 3));
    }

    @Test
    void aWalkThatRanOutOfTimeSaysHowLongHowFarAndWhatBlockedTheNearestApproach() {
        Blockage nearest = new Blockage(B, Blocks.STONE.defaultBlockState(), MoveKind.PILLAR, Reason.WOULD_COLLAPSE, null);
        String checkIn = NavText.timedOut(6000, 20.7, "1,2,3", nearest, true);
        assertTrue(checkIn.startsWith("timed out after 300 s of walking, 20.7 blocks from target (now at 1,2,3)")
                && checkIn.contains("nearest to the target, pillaring up at 120,65,-33 (stone) was ruled out: ")
                && checkIn.contains("call `numen.move.to` again") && !checkIn.contains("progress had stopped"), checkIn);
        String stalled = NavText.timedOut(900, 5, "1,2,3", null, false);
        assertTrue(stalled.contains("made no progress") && !stalled.contains("nearest to the target"), stalled);
    }
}
