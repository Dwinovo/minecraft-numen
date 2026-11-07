package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.BlockStorageReading;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.ItemInventory;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.ItemSlot;
import com.dwinovo.numen.api.platform.services.Exposures;
import com.dwinovo.numen.api.platform.services.IBlockCapabilityReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Paper 上的 {@link IBlockCapabilityReader}:只有原版的容器({@link Container} / {@link WorldlyContainer},
 * 以及堆肥桶那样的 {@link WorldlyContainerHolder}),没有流体与能量的通用接口,这两项是空的。
 *
 * <p>面的语义同原版漏斗:{@link WorldlyContainer} 从某一面看进去只有那一面的格子,别的容器从哪一面看都是整个容器;
 * 双箱读成合在一起的一个容器(原版漏斗、玩家点开看到的都是它)。
 */
public final class PaperBlockCapabilityReader implements IBlockCapabilityReader {

    @Override
    public BlockStorageReading read(Level level, BlockPos pos) {
        Container container = containerAt(level, pos);
        List<BlockStorageReading.Exposed<ItemInventory>> items = container == null ? List.of()
                : new Exposures<ItemInventory>().probe(side -> inventory(container, side)).found();
        return new BlockStorageReading(items, List.of(), null);
    }

    private static Container containerAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof WorldlyContainerHolder holder) {
            return holder.getContainer(state, level, pos);
        }
        if (level.getBlockEntity(pos) instanceof Container container) {
            if (container instanceof ChestBlockEntity && state.getBlock() instanceof ChestBlock chest) {
                return ChestBlock.getContainer(chest, state, level, pos, true);
            }
            return container;
        }
        return null;
    }

    /** 从 {@code side} 看进去的格子:分面的容器只露出那一面的格子,其余都是整个容器。 */
    private static ItemInventory inventory(Container container, Direction side) {
        List<ItemSlot> slots = new ArrayList<>();
        if (side != null && container instanceof WorldlyContainer sided) {
            for (int s : sided.getSlotsForFace(side)) {
                slots.add(slot(container.getItem(s)));
            }
        } else {
            for (int s = 0; s < container.getContainerSize(); s++) {
                slots.add(slot(container.getItem(s)));
            }
        }
        return new ItemInventory(slots);
    }

    private static ItemSlot slot(ItemStack stack) {
        return stack.isEmpty() ? ItemSlot.EMPTY
                : new ItemSlot(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
    }
}
