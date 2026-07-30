package com.dwinovo.numen.core.scaffold;

import org.junit.jupiter.api.Test;

public final class TemporaryScaffoldReclaimToolTest {
    @Test
    void reclaimsOnlyLedgerCoordinatesAsABackgroundTask() {
        TemporaryScaffoldReclaimTool tool = new TemporaryScaffoldReclaimTool();
        String description = tool.description().toLowerCase();

        require(
            tool.name().equals("reclaim_temporary_scaffolds"),
            "the public tool name must be stable"
        );
        require(
            description.contains("exact coordinates")
                && description.contains("temporary-scaffold ledger"),
            "the tool must reclaim only recorded temporary coordinates"
        );
        require(
            description.contains("never use mine") && description.contains("background"),
            "the tool must replace type-based mine and expose its asynchronous lifecycle"
        );
        require(
            !tool.parameterSchema().containsKey("block_ids")
                && !tool.parameterSchema().containsKey("count"),
            "the reclaim tool must not accept type-based mining scope"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
