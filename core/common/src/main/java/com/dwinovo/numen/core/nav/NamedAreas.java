package com.dwinovo.numen.core.nav;

import java.util.Map;
import java.util.stream.Collectors;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.area.AreaStore;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.spec.PositionCosts;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 寻路那一侧按名字找区域:她此刻所在的维度与主人名下的区域({@link AreaStore#all},取的那一刻的不可变快照)。路线的去处与路线标志里
 * 点名的区域都在这里解析——路线存的是名字,每次规划按当时的区域解析,区域改了跟着走,删了当场如实说。
 *
 * @param dimension 她此刻所在的维度:一块区域只属于一个维度,别的维度的区域在这里什么也不指
 * @param areas     主人名下的区域;还没有主人是空表
 */
public record NamedAreas(ResourceKey<Level> dimension, Map<String, Area> areas) {

    public NamedAreas {
        areas = Map.copyOf(areas);
    }

    /** 她此刻的维度与主人名下此刻的区域。在世界所在的线程上取。 */
    public static NamedAreas of(NumenPlayer her) {
        return new NamedAreas(her.level().dimension(),
                her.getOwnerUuid() == null ? Map.of() : AreaStore.of(her.getServer(), her.getOwnerUuid()).all());
    }

    /**
     * {@code ref} 指的区域(整块,或只剩那一部分)。
     *
     * @throws IllegalArgumentException 没有这块区域、区域里没有这一部分、区域在别的维度:说出事实与主人有哪些区域、哪些部分
     */
    public Area resolve(AreaRef ref) {
        Area found = find(ref);
        if (found != null) {
            return found;
        }
        Area area = areas.get(ref.name());
        if (area == null) {
            throw new IllegalArgumentException("there is no area named " + ref.name() + "; " + (areas.isEmpty()
                    ? "your owner has no areas yet (area.new makes one)"
                    : "your owner's areas are " + String.join(", ", areas.keySet().stream().sorted().toList())
                            + " (area.list() shows them)"));
        }
        if (!area.dimension().equals(dimension)) {
            throw new IllegalArgumentException("area " + ref.name() + " lies in " + area.dimension().location()
                    + ", and I am in " + dimension.location() + "; an area belongs to one dimension");
        }
        throw new IllegalArgumentException("area " + ref.name() + " has no part " + ref.part() + "; its parts are "
                + (area.parts().isEmpty() ? "none"
                        : area.parts().stream().map(Area.Part::id).collect(Collectors.joining(", ")))
                + " (area.show(\"" + ref.name() + "\") lists them)");
    }

    /** {@code ref} 指的区域;没有这块区域、没有这一部分、或它在别的维度,是 null。 */
    public Area find(AreaRef ref) {
        Area found = ref.resolve(areas);
        return found == null || !found.dimension().equals(dimension) ? null : found;
    }

    /**
     * 一块区域交给寻路的样子:只回答一格在不在里面({@link PositionCosts.Region})。问的是区域的小节位图,一次查表加一次取位;
     * 区域是不可变值,搜索线程拿着读。不逐格展开。
     */
    public static PositionCosts.Region region(Area area) {
        Cells cells = area.cells();
        return cell -> cells.contains(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell));
    }
}
