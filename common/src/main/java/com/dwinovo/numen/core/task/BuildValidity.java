package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared block-state validity rules for construction scans, placement, and path costs. */
public final class BuildValidity {

    private static final Set<Property<?>> ORIENTATION_PROPERTIES = Set.copyOf(List.of(
            RotatedPillarBlock.AXIS,
            HorizontalDirectionalBlock.FACING,
            StairBlock.FACING,
            StairBlock.HALF,
            StairBlock.SHAPE,
            PipeBlock.NORTH,
            PipeBlock.EAST,
            PipeBlock.SOUTH,
            PipeBlock.WEST,
            PipeBlock.UP,
            TrapDoorBlock.OPEN,
            TrapDoorBlock.HALF));

    private BuildValidity() {}

    public static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        NavSettings settings = NavSettings.get();
        if (current.getBlock() instanceof LiquidBlock && settings.okIfWater) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && settings.okIfAir().contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && settings.buildIgnoreBlocks().contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && settings.buildIgnoreExisting && !itemVerify) {
            return true;
        }
        if (!itemVerify && settings.buildValidSubstitutes()
                .getOrDefault(desired.getBlock(), List.of()).contains(current.getBlock())) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockState(current, desired);
    }

    public static boolean sameBlockState(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        NavSettings settings = NavSettings.get();
        List<String> ignoredProps = settings.buildIgnoreProperties();
        if (!settings.buildIgnoreDirection && ignoredProps.isEmpty()) {
            return first.equals(second);
        }
        Map<Property<?>, Comparable<?>> firstValues = first.getValues();
        Map<Property<?>, Comparable<?>> secondValues = second.getValues();
        for (Map.Entry<Property<?>, Comparable<?>> entry : firstValues.entrySet()) {
            Property<?> property = entry.getKey();
            if (entry.getValue() != secondValues.get(property)
                    && !(settings.buildIgnoreDirection && ORIENTATION_PROPERTIES.contains(property))
                    && !ignoredProps.contains(property.getName())) {
                return false;
            }
        }
        return true;
    }
}
