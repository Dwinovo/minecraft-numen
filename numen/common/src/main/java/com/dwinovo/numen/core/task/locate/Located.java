package com.dwinovo.numen.core.task.locate;

import com.dwinovo.numen.core.task.CompassUtil;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Place;
import net.minecraft.core.BlockPos;

import java.util.Optional;

/**
 * 找一处结构或群系的结论:{@code numen.locate.structure/biome} 交回的值。找到了是那一列、方向与水平距离;没找到是搜了多远、在哪个维度。
 */
@Doc("Where the nearest one is, or how far the search went without finding one.")
public record Located(@Doc("Whether one was found.") boolean found,
                      @Doc("The column it is in: numen.move.to takes it and finds the height on its own.")
                      Optional<Place> pos,
                      @Doc("Compass direction from where you stand.") Optional<String> direction,
                      @Doc("Blocks to it, horizontally.") Optional<Integer> horizontalDistance,
                      @Doc("How far out the search went when nothing was found; 0 means it does not generate in this "
                              + "dimension.") Optional<Integer> searched,
                      @Doc("The dimension searched: yours.") String dimension) {

    /** 找到了 {@code best},从 {@code me} 看过去。 */
    static Located at(BlockPos best, BlockPos me, String dimension) {
        int dx = best.getX() - me.getX();
        int dz = best.getZ() - me.getZ();
        int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
        return new Located(true, Optional.of(new Place(best.getX(), null, best.getZ())),
                Optional.of(CompassUtil.compass(dx, dz)), Optional.of(dist), Optional.empty(), dimension);
    }

    /** 搜了 {@code searched} 格远没找到。 */
    static Located none(int searched, String dimension) {
        return new Located(false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(searched),
                dimension);
    }
}
