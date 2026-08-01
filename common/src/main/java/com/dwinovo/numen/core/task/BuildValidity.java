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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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

    /**
     * 对账<b>只看</b>这些属性——白名单,不是黑名单。
     *
     * <p>一块方块的状态里,真正由"作者"决定的只有摆放姿态这一小撮:朝向、
     * 上下半、轴向、合页在左在右、附着面、旋转档位。其余绝大多数是<b>世界
     * 自己算出来的</b>:树叶的 distance、楼梯的拐角形状、栅栏与墙的连接、
     * 红石的电平与走向、草方块顶上有没有雪、门此刻开着还是关着……蓝图里存的
     * 这些值往往是过期或物理不可达的(投影/WorldEdit 粘贴时会把它们冻住),
     * 照字面比对必然陷进"放下去被世界改写、判不符、拆了重放、又被改写"的
     * 死循环——而且改写者不止是世界:我们自己的寻路为了过去就会推开刚装好
     * 的门。
     *
     * <p>这里刻意用白名单。黑名单是开放集合:每来一个新方块就可能带一个新的
     * 派生属性,只能被咬一次补一条,永远补不完(实测树叶、栅栏门开合、栅栏门
     * 朝向连着咬了三次)。白名单是封闭集合:作者能决定的姿态就这么十几种,
     * 一次定完,此后任何新方块都自动落在正确的一侧。
     *
     * <p>方块种类本身仍然严格比对(见 {@link #sameBlockState});被忽略的只是
     * 同一种方块内部那些不归我们管的状态位。
     */
    private static final Set<Property<?>> AUTHORED_PROPERTIES = Set.copyOf(List.of(
            BlockStateProperties.FACING,
            BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.AXIS,
            BlockStateProperties.HORIZONTAL_AXIS,
            BlockStateProperties.HALF,
            BlockStateProperties.DOUBLE_BLOCK_HALF,
            BlockStateProperties.SLAB_TYPE,
            BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.BELL_ATTACHMENT,
            BlockStateProperties.ROTATION_16,
            BlockStateProperties.ORIENTATION,
            BlockStateProperties.BED_PART));

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
        // 栅栏门的朝向会被"开门"这个动作本身改写:原版里从门朝向的反面推门,
        // 门会翻转过来面向你。所以对栅栏门而言朝向不是工程量,是开关的副作用,
        // 拿它对账同样会陷进"装好—被推开—重装"的拉锯。
        boolean gate = first.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock;
        for (Property<?> property : AUTHORED_PROPERTIES) {
            if (!first.hasProperty(property) || !second.hasProperty(property)) {
                continue;
            }
            if (gate && property == BlockStateProperties.HORIZONTAL_FACING) {
                continue;
            }
            if (settings.buildIgnoreDirection && ORIENTATION_PROPERTIES.contains(property)) {
                continue;
            }
            if (ignoredProps.contains(property.getName())) {
                continue;
            }
            if (first.getValue(property) != second.getValue(property)) {
                return false;
            }
        }
        return true;
    }
}
