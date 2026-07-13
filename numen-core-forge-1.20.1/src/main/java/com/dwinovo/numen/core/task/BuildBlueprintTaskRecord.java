package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.blueprint.BlueprintPlanner;
import net.minecraft.core.BlockPos;
import java.util.Map;

/** Descriptor for one resumable blueprint build invocation. */
public final class BuildBlueprintTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "build_blueprint";

    public final String blueprintName;
    public final BlockPos anchor;
    public final BlueprintPlanner.Turn turn;
    public final boolean creative;
    public final int batchLimit;
    public final Map<String, Integer> protectedItems;
    private int changed;
    private int skipped;

    public BuildBlueprintTaskRecord(String toolCallId, long deadlineGameTime,
                                    String blueprintName,
                                    BlockPos anchor,
                                    BlueprintPlanner.Turn turn,
                                    boolean creative,
                                    int batchLimit,
                                    Map<String, Integer> protectedItems) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.blueprintName = blueprintName;
        this.anchor = anchor.immutable();
        this.turn = turn;
        this.creative = creative;
        this.batchLimit = batchLimit;
        this.protectedItems = protectedItems == null ? Map.of() : Map.copyOf(protectedItems);
    }

    public int getChanged() { return changed; }
    public int getSkipped() { return skipped; }
    public void incrementChanged() { changed++; }
    public void incrementSkipped() { skipped++; }
    public void restoreProgress(int changed, int skipped) {
        this.changed = Math.max(0, Math.min(batchLimit, changed));
        this.skipped = Math.max(0, skipped);
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + blueprintName + " @ "
                + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ()
                + " rot=" + turn.id();
    }
}
