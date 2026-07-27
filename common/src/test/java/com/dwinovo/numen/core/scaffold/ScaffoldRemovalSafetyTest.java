package com.dwinovo.numen.core.scaffold;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.scaffold.ScaffoldRemovalSafety;
import com.dwinovo.numen.core.scaffold.ScaffoldRemovalSafety.Action;

public final class ScaffoldRemovalSafetyTest {
    @Test
    void verifiedRuntimeBehavior() {
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
                true
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
                true
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
                true
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
                false
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
                true
            )
        );
        require(onlyRetreat.action() == Action.KEEP, "only known retreat must remain");
        require(
            onlyRetreat.reason().equals("only_known_retreat"),
            "retreat preservation needs an explicit reason: " + onlyRetreat.reason()
        );

        var dangerousDrop = ScaffoldRemovalSafety.evaluate(
            new ScaffoldRemovalSafety.Context(
                true,
                true,
                false,
                false,
                true,
                false,
                6,
                false,
                true
            )
        );
        require(dangerousDrop.action() == Action.KEEP, "unsafe fall must remain bridged");
        require(
            dangerousDrop.reason().equals("unsafe_fall_below"),
            "unsafe fall needs an explicit reason: " + dangerousDrop.reason()
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
            false
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
            false
        );
        require(
            !ScaffoldRemovalSafety.canNavigateForRemoval(unreachableButRequiredByPath),
            "navigation must not reclaim a block required by an active path"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
