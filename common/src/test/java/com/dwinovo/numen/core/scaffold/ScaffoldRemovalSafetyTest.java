package com.dwinovo.numen.core.scaffold;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.scaffold.ScaffoldRemovalSafety;
import com.dwinovo.numen.core.scaffold.ScaffoldRemovalSafety.Action;

public final class ScaffoldRemovalSafetyTest {
    @Test
    void preservesRealCrossingsAndReclaimsIsolatedSupports() {
        var supportingOverLava = ScaffoldRemovalSafety.evaluate(
            new ScaffoldRemovalSafety.Context(
                true,
                true,
                true,
                false,
                true,
                true,
                4,
                false,
                true,
                0
            )
        );
        require(supportingOverLava.action() == Action.KEEP, "support over lava must remain");
        require(
            supportingOverLava.reason().equals("supports_ai_over_hazard"),
            "unsafe support needs an explicit reason: " + supportingOverLava.reason()
        );

        var requiredByPath = ScaffoldRemovalSafety.evaluate(
            new ScaffoldRemovalSafety.Context(
                true,
                true,
                false,
                true,
                true,
                false,
                1,
                false,
                true,
                0
            )
        );
        require(requiredByPath.action() == Action.KEEP, "active path support must remain");
        require(
            requiredByPath.reason().equals("required_by_active_path"),
            "path dependency needs an explicit reason: " + requiredByPath.reason()
        );

        var changedByWorld = ScaffoldRemovalSafety.evaluate(
            new ScaffoldRemovalSafety.Context(
                true,
                false,
                false,
                false,
                true,
                false,
                0,
                false,
                true,
                0
            )
        );
        require(changedByWorld.action() == Action.FORGET, "changed coordinates must never be mined");
        require(
            changedByWorld.reason().equals("placed_block_changed"),
            "changed coordinate needs an explicit reason: " + changedByWorld.reason()
        );

        var unloaded = ScaffoldRemovalSafety.evaluate(
            new ScaffoldRemovalSafety.Context(
                false,
                true,
                false,
                false,
                false,
                false,
                0,
                false,
                false,
                0
            )
        );
        require(unloaded.action() == Action.KEEP, "unloaded chunk must be retried later");
        require(
            unloaded.reason().equals("chunk_not_loaded"),
            "unknown world state needs an explicit reason: " + unloaded.reason()
        );

        var onlyRetreat = ScaffoldRemovalSafety.evaluate(
            new ScaffoldRemovalSafety.Context(
                true,
                true,
                false,
                false,
                true,
                false,
                2,
                true,
                true,
                0
            )
        );
        require(onlyRetreat.action() == Action.KEEP, "only known retreat must remain");
        require(
            onlyRetreat.reason().equals("only_known_retreat"),
            "retreat preservation needs an explicit reason: " + onlyRetreat.reason()
        );

        var isolatedHighScaffold = ScaffoldRemovalSafety.evaluate(
            new ScaffoldRemovalSafety.Context(
                true,
                true,
                false,
                false,
                true,
                false,
                6,
                false,
                true,
                0
            )
        );
        require(
            isolatedHighScaffold.action() == Action.REMOVE,
            "an isolated high scaffold cannot make the companion fall once it is no longer in use"
        );
        require(
            isolatedHighScaffold.reason().equals("safe_to_remove"),
            "an unused isolated scaffold must be reclaimed: " + isolatedHighScaffold.reason()
        );

        var lavaBridge = ScaffoldRemovalSafety.evaluate(
            new ScaffoldRemovalSafety.Context(
                true,
                true,
                false,
                false,
                true,
                true,
                1,
                false,
                true,
                2
            )
        );
        require(lavaBridge.action() == Action.KEEP, "a real bridge over lava must remain");
        require(
            lavaBridge.reason().equals("useful_walkable_crossing"),
            "a real lava bridge needs a geometric preservation reason: " + lavaBridge.reason()
        );

        var waterBridge = ScaffoldRemovalSafety.evaluate(
            new ScaffoldRemovalSafety.Context(
                true,
                true,
                false,
                false,
                true,
                false,
                0,
                false,
                true,
                2
            )
        );
        require(waterBridge.action() == Action.KEEP, "a real bridge over water must remain");

        var ravineBridge = ScaffoldRemovalSafety.evaluate(
            new ScaffoldRemovalSafety.Context(
                true,
                true,
                false,
                false,
                true,
                false,
                6,
                false,
                true,
                2
            )
        );
        require(ravineBridge.action() == Action.KEEP, "a real bridge across a ravine must remain");

        var redundantGroundPath = ScaffoldRemovalSafety.evaluate(
            new ScaffoldRemovalSafety.Context(
                true,
                true,
                false,
                false,
                true,
                false,
                1,
                false,
                true,
                2
            )
        );
        require(
            redundantGroundPath.action() == Action.REMOVE,
            "two adjacent walkable sides alone must not preserve a redundant block over safe ground"
        );

        var safelyOutOfReach = new ScaffoldRemovalSafety.Context(
            true,
            true,
            false,
            false,
            true,
            false,
            1,
            false,
            false,
            0
        );
        require(
            ScaffoldRemovalSafety.canNavigateForRemoval(safelyOutOfReach),
            "an otherwise safe tracked block must be approachable from another cleanup stance"
        );

        var unreachableButRequiredByPath = new ScaffoldRemovalSafety.Context(
            true,
            true,
            false,
            true,
            true,
            false,
            1,
            false,
            false,
            0
        );
        require(
            !ScaffoldRemovalSafety.canNavigateForRemoval(unreachableButRequiredByPath),
            "navigation must not reclaim a block required by an active path"
        );

        var outOfReachLavaBridge = new ScaffoldRemovalSafety.Context(
            true,
            true,
            false,
            false,
            true,
            true,
            0,
            false,
            false,
            2
        );
        require(
            !ScaffoldRemovalSafety.canNavigateForRemoval(outOfReachLavaBridge),
            "cleanup navigation must not approach a genuine lava bridge in order to dismantle it"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
