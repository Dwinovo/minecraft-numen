package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.tools.CraftingPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Map;

/** Restart-safe descriptor for an executable recursive crafting job. */
public final class CraftItemsTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "craft_items";

    public final Item target;
    public final int count;
    public final int maxDepth;
    public final List<CraftingPlanner.Step> steps;
    private int baseline = -1;
    private int produced;
    private int stepIndex;
    private int batchIndex;
    private Phase phase = Phase.READY;
    private BlockPos stationPos;
    private int actionOutputBaseline;
    private int machineInputBaseline;
    private int machineOutputBaseline;
    private Item machineFuel;
    private Map<Item, Integer> actionInputBaselines = Map.of();

    public enum Phase {
        READY, CRAFT_COMMIT, MACHINE_DEPOSIT, MACHINE_REFUEL, MACHINE_WAIT, MACHINE_COLLECT,
        CAMPFIRE_PLACE, CAMPFIRE_WAIT
    }

    public CraftItemsTaskRecord(String toolCallId, long deadlineGameTime,
                                Item target, int count, int maxDepth,
                                List<CraftingPlanner.Step> steps) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.target = target;
        this.count = Math.max(1, Math.min(999, count));
        this.maxDepth = Math.max(1, Math.min(5, maxDepth));
        this.steps = List.copyOf(steps);
    }

    public int getBaseline() { return baseline; }
    public int getProduced() { return produced; }
    public int getStepIndex() { return stepIndex; }
    public int getBatchIndex() { return batchIndex; }
    public Phase getPhase() { return phase; }
    public BlockPos getStationPos() { return stationPos; }
    public int getActionOutputBaseline() { return actionOutputBaseline; }
    public int getMachineInputBaseline() { return machineInputBaseline; }
    public int getMachineOutputBaseline() { return machineOutputBaseline; }
    public Item getMachineFuel() { return machineFuel; }
    public Map<Item, Integer> getActionInputBaselines() { return actionInputBaselines; }
    public void setBaseline(int baseline) { this.baseline = Math.max(0, baseline); }
    public void setProduced(int produced) { this.produced = Math.max(0, produced); }
    public void setProgress(int stepIndex, int batchIndex) {
        this.stepIndex = Math.max(0, stepIndex);
        this.batchIndex = Math.max(0, batchIndex);
    }
    public void checkpoint(Phase phase, BlockPos stationPos, int outputBaseline,
                           int inputBaseline, int machineOutputBaseline) {
        this.phase = phase;
        this.stationPos = stationPos == null ? null : stationPos.immutable();
        this.actionOutputBaseline = Math.max(0, outputBaseline);
        this.machineInputBaseline = Math.max(0, inputBaseline);
        this.machineOutputBaseline = Math.max(0, machineOutputBaseline);
    }
    public void setActionInputBaselines(Map<Item, Integer> baselines) {
        this.actionInputBaselines = Map.copyOf(baselines == null ? Map.of() : baselines);
    }
    public void setMachineFuel(Item fuel) { this.machineFuel = fuel; }
    public void clearCheckpoint() {
        phase = Phase.READY;
        stationPos = null;
        actionOutputBaseline = 0;
        machineInputBaseline = 0;
        machineOutputBaseline = 0;
        machineFuel = null;
        actionInputBaselines = Map.of();
    }

    @Override public String describe() {
        return TOOL_NAME + " " + net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(target).getPath() + " " + produced + "/" + count;
    }
}
