package com.dwinovo.numen.sdk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 世界里的一格方块,脚本里的类 {@code Block}:它的 id 与位置。要一格的参数收下它就是它的 {@code pos}。
 *
 * @param name 方块 id
 * @param pos  那一格
 */
@Doc("One block in the world.")
public record BlockAt(@Doc("Its id, minecraft:iron_ore.") String name, @Doc("Its cell.") BlockPos pos) {

    /** 这一格此刻的方块。 */
    public static BlockAt of(BlockPos pos, BlockState state) {
        return new BlockAt(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), pos.immutable());
    }
}
