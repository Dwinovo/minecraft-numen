package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.List;
import java.util.Objects;

/** Typed descriptor for a bounded multi-block construction job. */
public final class BuildTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "build";

    public final List<Target> targets;
    public final boolean replaceExisting;
    public final int layerHeight;

    private int placed;
    private int broken;
    private int completed;

    public BuildTaskRecord(String toolCallId, long deadlineGameTime,
                           List<Target> targets, boolean replaceExisting, int layerHeight) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.targets = List.copyOf(targets);
        this.replaceExisting = replaceExisting;
        this.layerHeight = layerHeight;
    }

    public int placed() {
        return placed;
    }

    public void placedOne() {
        placed++;
    }

    public int broken() {
        return broken;
    }

    public void brokeOne() {
        broken++;
    }

    public int completed() {
        return completed;
    }

    public void completed(int completed) {
        this.completed = completed;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + completed + "/" + targets.size();
    }

    public record Target(BlockState desiredState, Item item, BlockPos pos, String label,
                         Direction facing, Direction.Axis axis, Boolean topHalf) {
        public Target(Block block, Item item, BlockPos pos, String label,
                      Direction facing, Direction.Axis axis, Boolean topHalf) {
            this(applyHints(block.defaultBlockState(), facing, axis, topHalf),
                    item, pos, label, facing, axis, topHalf);
        }

        public Target {
            desiredState = Objects.requireNonNull(desiredState, "desiredState");
            item = Objects.requireNonNull(item, "item");
            pos = Objects.requireNonNull(pos, "pos").immutable();
            if (facing != null && facingOf(desiredState) == null) {
                throw new IllegalArgumentException(desiredState.getBlock().getName().getString()
                        + " does not support facing");
            }
            if (axis != null && axisOf(desiredState) == null) {
                throw new IllegalArgumentException(desiredState.getBlock().getName().getString()
                        + " does not support axis");
            }
            if (topHalf != null && topHalfOf(desiredState) == null) {
                throw new IllegalArgumentException(desiredState.getBlock().getName().getString()
                        + " does not support top/bottom half");
            }
            label = label == null || label.isBlank()
                    ? desiredState.getBlock().getName().getString()
                    : label;
        }

        public Block block() {
            return desiredState.getBlock();
        }

        public boolean matches(BlockState state) {
            return BuildValidity.valid(state, desiredState, false);
        }

        public boolean acceptsPlacedState(BlockState state) {
            return BuildValidity.valid(state, desiredState, true);
        }

        public String shortPos() {
            return pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
    }

    private static BlockState applyHints(BlockState state, Direction facing,
                                         Direction.Axis axis, Boolean topHalf) {
        if (facing != null) {
            if (state.hasProperty(BlockStateProperties.FACING)) {
                state = state.setValue(BlockStateProperties.FACING, facing);
            } else if (facing.getAxis().isHorizontal()
                    && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            }
        }
        if (axis != null) {
            if (state.hasProperty(BlockStateProperties.AXIS)) {
                state = state.setValue(BlockStateProperties.AXIS, axis);
            } else if (axis.isHorizontal() && state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
                state = state.setValue(BlockStateProperties.HORIZONTAL_AXIS, axis);
            }
        }
        if (topHalf != null) {
            if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
                state = state.setValue(BlockStateProperties.SLAB_TYPE,
                        topHalf ? SlabType.TOP : SlabType.BOTTOM);
            }
            if (state.hasProperty(BlockStateProperties.HALF)) {
                state = state.setValue(BlockStateProperties.HALF,
                        topHalf ? Half.TOP : Half.BOTTOM);
            }
        }
        return state;
    }

    private static Direction facingOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.FACING)) {
            return s.getValue(BlockStateProperties.FACING);
        }
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return s.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return null;
    }

    private static Direction.Axis axisOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.AXIS)) {
            return s.getValue(BlockStateProperties.AXIS);
        }
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            return s.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        }
        return null;
    }

    private static Boolean topHalfOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType t = s.getValue(BlockStateProperties.SLAB_TYPE);
            return t == SlabType.DOUBLE ? null : t == SlabType.TOP;
        }
        if (s.hasProperty(BlockStateProperties.HALF)) {
            return s.getValue(BlockStateProperties.HALF) == Half.TOP;
        }
        return null;
    }
}