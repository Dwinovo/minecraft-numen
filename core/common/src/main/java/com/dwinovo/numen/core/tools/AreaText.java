package com.dwinovo.numen.core.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.core.task.CompassUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * 区域与它的部分怎么说给模型听,只在这一处:{@code scan blocks} 列出的每一团与 {@code area show} 列出的每一部分是同一种一行
 * (一个 JSON 对象),格数、附带的方块、最近一格、小的逐格列坐标都由 {@link #part} 写;坐标、盒子、方向的写法也在这里。
 */
public final class AreaText {

    /**
     * 不超过这么多格的一部分逐格列出坐标,更大的只给摘要(格数、最近一格)。要够一圈末地传送门框架的 12 格
     * (stronghold_finding 靠它找门),够一棵普通树或一小撮矿;再多就是清单不是事实了。
     */
    public static final int LIST_CELLS_UP_TO = 16;
    /** 盒子两角之间的分隔:{@code x1,y1,z1..x2,y2,z2}。 */
    public static final String BOX_SEPARATOR = "..";

    private AreaText() {}

    /**
     * 一部分(一团)的事实:编号(有的话)、格数、附带方块的各种格数(扫描时看到的)、流体的源头格数、离 {@code from} 最近的一格
     * (方向与距离),小的逐格列坐标(由近及远)。许不许挖由调用方接上:扫描时的说法,或此刻问的。
     *
     * @param id   这一部分怎么点名({@code ores/g3});没有编号(只是看、没存)为 null
     * @param from 她此刻脚下那一格:方向与距离从这里量;区域在别的维度时为 null,不说最近一格
     */
    public static JsonObject part(String id, Cells cells, BlockPos from) {
        JsonObject o = new JsonObject();
        if (id != null) {
            o.addProperty("id", id);
        }
        o.addProperty("cells", cells.size());
        Map<Block, Integer> counts = new LinkedHashMap<>();
        int[] fluids = new int[2];
        List<BlockPos> positions = new ArrayList<>();
        boolean list = cells.size() <= LIST_CELLS_UP_TO;
        cells.forEach((x, y, z, seen) -> {
            if (seen != null) {
                counts.merge(seen.state().getBlock(), 1, Integer::sum);
                if (!seen.state().getFluidState().isEmpty()) {
                    fluids[0]++;
                    if (seen.state().getFluidState().isSource()) {
                        fluids[1]++;
                    }
                }
            }
            if (list) {
                positions.add(new BlockPos(x, y, z));
            }
        });
        if (!counts.isEmpty()) {
            List<Map.Entry<Block, Integer>> byCount = new ArrayList<>(counts.entrySet());
            byCount.sort(Collections.reverseOrder(Map.Entry.comparingByValue()));
            JsonObject blocks = new JsonObject();
            for (Map.Entry<Block, Integer> e : byCount) {
                blocks.addProperty(BuiltInRegistries.BLOCK.getKey(e.getKey()).toString(), e.getValue());
            }
            o.add("blocks", blocks);
        }
        // 源头与流动的是流体的关键一位:铸黑曜石、灌桶都要源头
        if (fluids[0] > 0) {
            o.addProperty("sources", fluids[1]);
        }
        BlockPos nearest = from == null ? null : cells.nearest(from);
        if (nearest != null) {
            JsonObject at = xyz(nearest);
            at.addProperty("direction", direction(from, nearest));
            at.addProperty("distance", Math.round(Math.sqrt(from.distSqr(nearest)) * 10) / 10.0);
            o.add("nearest", at);
        }
        if (list) {
            if (from != null) {
                positions.sort(Comparator.comparingDouble(from::distSqr));
            }
            JsonArray cellsOut = new JsonArray();
            positions.forEach(p -> cellsOut.add(cell(p)));
            o.add("positions", cellsOut);
        }
        return o;
    }

    /** 一整块区域的小结:维度、几部分(编号)、多少格、包围盒。 */
    public static String summary(String name, Area area) {
        List<String> ids = area.parts().stream().map(Area.Part::id).toList();
        BoundingBox box = area.cells().bounds();
        return name + " in " + area.dimension().location() + ": " + (ids.isEmpty() ? "no parts"
                : ids.size() + " part(s) (" + String.join(", ", ids) + ")") + ", " + area.cells().size() + " cells"
                + (box == null ? "" : ", box " + box(box));
    }

    /** 包围盒写成 {@code x1,y1,z1..x2,y2,z2}:{@code area add --box} 收的就是这个写法。 */
    public static String box(BoundingBox box) {
        return box.minX() + "," + box.minY() + "," + box.minZ() + BOX_SEPARATOR + box.maxX() + "," + box.maxY() + ","
                + box.maxZ();
    }

    /** 从 {@code from} 看那一格:水平方位({@link CompassUtil})加上下几格;正好在那里是 {@code here}。 */
    public static String direction(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        List<String> parts = new ArrayList<>(2);
        if (dx != 0 || dz != 0) {
            parts.add(CompassUtil.compass(dx, dz));
        }
        if (dy != 0) {
            parts.add(Math.abs(dy) + (dy > 0 ? " up" : " down"));
        }
        return parts.isEmpty() ? "here" : String.join(", ", parts);
    }

    /** 一格写成 {@code x,y,z}。 */
    public static String cell(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static JsonObject xyz(BlockPos p) {
        JsonObject o = new JsonObject();
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        return o;
    }
}
