package com.dwinovo.numen.core.nav;

import java.util.List;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.drive.Blockage;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Heading;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.Reason;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.ConsentItem;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

    @Test
    void aCandidateLineListsItsBreaksWithWhyTheyNeedConsentAndItsPlaces() {
        ConsentItem asks = new ConsentItem(Action.Kind.BREAK, A, ConsentItem.NO_ENTITY, "oak_planks", Items.OAK_PLANKS,
                Component.literal("Oak Planks"), "break(placed)", "placed by a player", Component.literal("placed"),
                false, null);
        Stance ground = new Stance(Stance.Kind.GROUND, 64, 63);
        List<Edit> edits = List.of(new Edit.Dig(A, planks(), Permit.ask(asks), false, true),
                new Edit.Dig(B, planks(), Permit.ask(asks), false, true),
                new Edit.Place(C, Blocks.AIR.defaultBlockState(), Blocks.COBBLESTONE, Permit.ALLOW));
        Maneuver step = new Maneuver(MoveKind.WALK, Heading.CARDINAL.get(2), A.west(), ground, A, ground, false, false,
                false, false, 1, 0, 0, 1, edits, new long[0], 0, A.below());
        Route route = new Route(A.west(), ground, List.of(new Route.Leg(step, 50)));
        assertEquals("  r3  1 step  break 2 oak_planks (120,64,-33; 120,65,-33) needing consent (placed by a player)"
                + "  place 1 cobblestone (121,64,-33)", NavText.line("r3", route));
        assertEquals("no terrain change", NavText.planned(new Route(A, ground, List.of())));
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

    @Test
    void aPlanWithNoRouteUnderItsSpecTeachesTheFlagThatWouldShowOne() {
        String natural = NavText.unplanned(new Outcome.NeedsAlter(RouteSpec.Alter.NATURAL, 2), null, A, C,
                RouteSpec.defaults());
        assertTrue(natural.contains("without altering terrain") && natural.contains("changes 2 block(s)")
                && natural.contains("plan again with --alter natural"), natural);
        String consent = NavText.unplanned(new Outcome.NeedsAlter(RouteSpec.Alter.ANY, 3), null, A, C,
                RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build());
        assertTrue(consent.contains("owner's consent") && consent.contains("plan again with --alter any"), consent);
    }

    @Test
    void anOverBudgetWalkSaysWhatTheCheapestRouteWouldChange() {
        assertEquals("no route within an alter_budget of 1 (the cheapest found would change 3 blocks)",
                NavText.overBudget(1, 3));
    }
}
