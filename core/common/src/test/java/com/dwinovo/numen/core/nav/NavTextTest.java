package com.dwinovo.numen.core.nav;

import java.util.List;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.drive.Blockage;
import com.dwinovo.numen.pathing.drive.EditLedger;
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

    @Test
    void theActualLedgerNamesEveryBlockAndCellAndWhatTheBodyDid() {
        List<EditLedger.Entry> entries = List.of(
                new EditLedger.Dug(A, planks(), Permit.ALLOW),
                new EditLedger.Dug(B, planks(), Permit.ALLOW),
                new EditLedger.Placed(C, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), null),
                new EditLedger.Toggled(C.above(), Blocks.OAK_DOOR.defaultBlockState(), Blocks.OAK_DOOR.defaultBlockState(),
                        null));
        List<BodyAction> actions = List.of(new BodyAction.Dismounted(EntityType.BOAT),
                new BodyAction.Held(Items.COBBLESTONE, 3, 3), new BodyAction.Held(Items.COBBLESTONE, 3, 3));
        assertEquals("En route I had to break 2 oak_planks (120,64,-33; 120,65,-33) and place 1 cobblestone"
                + " (121,64,-33). I also stepped off the boat and switched to cobblestone in my hotbar (2 times).",
                NavText.journey(entries, actions));
        assertEquals("", NavText.journey(List.of(), List.of()), "什么都没做就什么都不说");
        assertEquals("En route I took a stack of dirt from the creative inventory.",
                NavText.journey(List.of(), List.of(new BodyAction.Conjured(Items.DIRT, 0))), "只有身体动作也要说");
    }

    @Test
    void waterPouredToBreakAFallAndScoopedBackIsToldAsSuch() {
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        String said = NavText.journey(List.of(new EditLedger.Placed(C, air, water, null),
                new EditLedger.Placed(C, water, air, null)), List.of());
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

    /** 没走到的是路线上的一段时,下一步写成改这条路线的命令:点名路线,多于一段时点名是哪一段。 */
    @Test
    void onARouteTheNextStepIsTheLineThatChangesThatRoute() {
        RouteSpec spec = RouteSpec.defaults();
        String alone = NavText.failure(new Outcome.NeedsAlter(RouteSpec.Alter.NATURAL, 2), null, A, C, spec,
                new NavText.OnRoute("goto-aria", 1, 1));
        assertTrue(alone.contains("`route spec goto-aria --alter natural`") && alone.contains("`route plan goto-aria`")
                && alone.contains("changing 2 block(s)"), alone);
        String leg = NavText.failure(new Outcome.NeedsAlter(RouteSpec.Alter.ANY, 3), null, A, C, spec,
                new NavText.OnRoute("home", 2, 3));
        assertTrue(leg.contains("`route spec home --leg 2 --alter any`") && leg.contains("asks the owner first"), leg);
        String budget = NavText.failure(new Outcome.OverAlterBudget(5), null, A, C,
                spec.edit().alter(RouteSpec.Alter.NATURAL).alterBudget(1).build(), new NavText.OnRoute("home", 1, 1));
        assertTrue(budget.contains("`route spec home --alter_budget 5`"), budget);
        String sight = NavText.failure(new Outcome.NoLineOfSight(B), null, A, C, spec, new NavText.OnRoute("home", 1, 1));
        assertTrue(sight.contains("`move go home` again"), sight);
    }

    @Test
    void everyOutcomeMapsToOneFailureKind() {
        assertEquals(FailureType.NO_PATH, NavText.type(new Outcome.NoRoute()));
        assertEquals(FailureType.NO_PATH, NavText.type(new Outcome.OutOfBudget()));
        assertEquals(FailureType.NO_PATH, NavText.type(new Outcome.Unloaded()));
        assertEquals(FailureType.NO_PATH, NavText.type(new Outcome.OverAlterBudget(4)));
        assertEquals(FailureType.TERRAIN_BLOCKED, NavText.type(new Outcome.NeedsAlter(RouteSpec.Alter.NATURAL, 2)));
        assertEquals(FailureType.NO_MATERIAL, NavText.type(new Outcome.NoMaterials()));
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
                NavText.journey(List.of(), List.of(new BodyAction.Held(Items.AIR, 8, 8))));
    }

    @Test
    void onlyASearchedOutWalkSaysEveryCellWasSearchedAndARunOutBudgetIsNoProof() {
        String none = NavText.failure(new Outcome.NoRoute(), null, A, C, RouteSpec.defaults());
        assertTrue(none.contains("every reachable cell was searched"), none);
        String budget = NavText.failure(new Outcome.OutOfBudget(), null, A, C, RouteSpec.defaults());
        assertTrue(budget.contains("not proof there is none") && !budget.contains("every reachable cell"), budget);
    }

    /** 每一种没走到各说各的原因与下一步,不并成同一句"到不了"。 */
    @Test
    void everyWayOfNotGettingThereSaysItsOwnReasonAndWhatToTryNext() {
        RouteSpec spec = RouteSpec.defaults();
        String none = NavText.failure(new Outcome.NoRoute(), null, A, C, spec);
        String budget = NavText.failure(new Outcome.OutOfBudget(), null, A, C, spec);
        String unloaded = NavText.failure(new Outcome.Unloaded(), null, A, C, spec);
        String alter = NavText.failure(new Outcome.NeedsAlter(RouteSpec.Alter.NATURAL, 2), null, A, C, spec);
        String denied = NavText.failure(new Outcome.Denied(B, "no"), null, A, C, spec);
        String stranded = NavText.failure(new Outcome.Stranded(A, planks()), null, A, C, spec);
        String blocked = NavText.failure(new Outcome.Blocked(new Blockage(B, planks(), MoveKind.WALK, Reason.NO_CLEARANCE,
                null)), null, A, C, spec);
        String sight = NavText.failure(new Outcome.NoLineOfSight(B), null, A, C, spec);
        assertTrue(none.contains("pick another destination"), none);
        assertTrue(budget.contains("not proof there is none") && budget.contains("a nearer waypoint"), budget);
        assertTrue(unloaded.contains("not loaded") && unloaded.contains("walk toward it and try again"), unloaded);
        assertTrue(alter.contains("walk with alter:'natural'"), alter);
        assertTrue(denied.contains("120,65,-33 is refused") && denied.contains("ask your owner"), denied);
        assertTrue(stranded.contains("can't stand where I am") && stranded.contains("free me first"), stranded);
        assertTrue(blocked.contains("no room for my body there") && blocked.contains("try again"), blocked);
        assertTrue(sight.contains("went out of sight") && sight.contains("move_goto x:120 y:65 z:-33 arrive:use"), sight);
        assertEquals(8, java.util.Set.of(none, budget, unloaded, alter, denied, stranded, blocked, sight).size());
    }

    @Test
    void anOverBudgetWalkSaysWhatTheCheapestRouteWouldChange() {
        assertEquals("no route within an alter_budget of 1 (the cheapest found would change 3 blocks)",
                NavText.overBudget(1, 3));
    }
}
