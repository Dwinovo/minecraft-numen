package com.dwinovo.numen.core.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.core.task.CompassUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * 区域与它的部分的数据与说法,只在这一处:{@code scan.blocks} 找到的每一团与 {@code area.show} 的每一部分是同一种表
 * ({@link #PART_CLASS}:格数、附带的方块、最近一格、小的逐格列坐标,由 {@link #part} 写),一整块是 {@link #AREA_CLASS}
 * ({@link #info});位置一律是 Pos。回执里那句话的坐标、盒子、方向的写法也在这里。
 */
public final class AreaText {

    /**
     * 不超过这么多格的一部分逐格列出坐标,更大的只给摘要(格数、最近一格)。要够一圈末地传送门框架的 12 格
     * (stronghold_finding 靠它找门),够一棵普通树或一小撮矿;再多就是清单不是事实了。
     */
    public static final int LIST_CELLS_UP_TO = 16;
    /**
     * 这种一行一团(一部分)的清单一页至多多少字节({@link com.dwinovo.numen.cli.Listing#maxBytes})。整份输出的 50 KB 是照读文件
     * 定的,一团一行时一页能放下近两百团、一万五千多 token,而且回执留在之后每一轮的输入里——真机一次 64 格的扫描扫到 196 团,
     * 一轮输入从 1.1 万涨到 3.3 万。清单由近及远,她下一步用得上的是开头几团:一行 150–400 字节(小团逐格列坐标),8 KB 一页是
     * 二三十到五十团、两三千 token,读得完;要更远的翻页(只是看时)或 {@code area show}(扫进区域后)。
     */
    public static final int PAGE_BYTES = 8 * 1024;

    /** 一块区域。 */
    public static final ScriptType.Class AREA_CLASS = new ScriptType.Class("Area", "One of your owner's areas.", null,
            List.of(ScriptType.field("name", ScriptType.STRING, null),
                    ScriptType.field("dimension", ScriptType.STRING, null),
                    ScriptType.field("parts", ScriptType.listOf(ScriptType.STRING), "Its parts, area/part (ores/g1)."),
                    ScriptType.field("count", ScriptType.INTEGER, "How many cells in all."),
                    ScriptType.optional("box", ScriptType.listOf(Shapes.POS.type()),
                            "Two corners of the box around it."),
                    ScriptType.optional("added", ScriptType.STRING, "The part this call added (area.add).")));

    /** 一部分,或扫描找到的一团。 */
    public static final ScriptType.Class PART_CLASS = new ScriptType.Class("AreaPart",
            "One part of an area, or one group of touching blocks a scan found. Anything that takes a place takes its "
                    + "id; work.dig and move.goto_ take its nearest as it is.", null,
            List.of(ScriptType.optional("id", ScriptType.STRING, "area/part (ores/g3), when it is kept in an area."),
                    ScriptType.field("count", ScriptType.INTEGER, "How many cells."),
                    ScriptType.optional("blocks", new ScriptType.Simple("table<string, integer>"),
                            "Cells per block type, as seen when added."),
                    ScriptType.optional("sources", ScriptType.INTEGER, "Source cells of a fluid."),
                    ScriptType.optional("nearest", ScriptType.table(
                            ScriptType.field("pos", Shapes.POS.type(), null),
                            ScriptType.optional("name", ScriptType.STRING, "The block seen there."),
                            ScriptType.field("direction", ScriptType.STRING, "From where you stand."),
                            ScriptType.field("distance", ScriptType.NUMBER, null)), "Its cell nearest to you."),
                    ScriptType.optional("positions", ScriptType.listOf(Shapes.POS.type()),
                            "Every cell, nearest first, for parts of up to " + LIST_CELLS_UP_TO + " cells."),
                    ScriptType.optional("box", ScriptType.listOf(Shapes.POS.type()), "Two corners of the box around it."),
                    ScriptType.optional("permission", new ScriptType.Simple("string|table<string, integer>"),
                            "Breaking it: allow, ask (your owner is asked first) or deny; mixed cells give a count per "
                                    + "answer."),
                    ScriptType.optional("reason", ScriptType.STRING, "Why, when it is not allow.")));

    private AreaText() {}

    /** 一整块区域的数据({@link #AREA_CLASS})。 */
    public static JsonObject info(String name, Area area) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("dimension", area.dimension().location().toString());
        JsonArray parts = new JsonArray();
        area.parts().forEach(p -> parts.add(name + "/" + p.id()));
        o.add("parts", parts);
        o.addProperty("count", area.cells().size());
        BoundingBox box = area.cells().bounds();
        if (box != null) {
            o.add("box", boxJson(box));
        }
        return o;
    }

    /** 包围盒的两个对角,两个 Pos。 */
    public static JsonArray boxJson(BoundingBox box) {
        JsonArray corners = new JsonArray();
        corners.add(Shapes.pos(new BlockPos(box.minX(), box.minY(), box.minZ())));
        corners.add(Shapes.pos(new BlockPos(box.maxX(), box.maxY(), box.maxZ())));
        return corners;
    }

    /**
     * 一部分(一团)的事实:编号(有的话)、格数、附带方块的各种格数(扫描时看到的)、流体的源头格数、离 {@code from} 最近的一格
     * (方向与距离),小的逐格列坐标(由近及远)。许不许挖由调用方接上:扫描时的说法,或此刻问的。
     *
     * @param id   这一部分怎么点名({@code ores/g3});没有编号(只是看、没存)为 null
     * @param from 她此刻脚下那一格:方向与距离从这里量
     */
    public static JsonObject part(String id, Cells cells, BlockPos from) {
        JsonObject o = new JsonObject();
        if (id != null) {
            o.addProperty("id", id);
        }
        o.addProperty("count", cells.size());
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
        BlockPos nearest = cells.nearest(from);
        if (nearest != null) {
            JsonObject at = new JsonObject();
            at.add("pos", Shapes.pos(nearest));
            Cells.Seen seen = cells.seenAt(nearest);
            if (seen != null) {
                at.addProperty("name", BuiltInRegistries.BLOCK.getKey(seen.state().getBlock()).toString());
            }
            at.addProperty("direction", direction(from, nearest));
            at.addProperty("distance", Math.round(Math.sqrt(from.distSqr(nearest)) * 10) / 10.0);
            o.add("nearest", at);
        }
        if (list) {
            positions.sort(Comparator.comparingDouble(from::distSqr));
            JsonArray cellsOut = new JsonArray();
            positions.forEach(p -> cellsOut.add(Shapes.pos(p)));
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

    /** 包围盒在那句话里写成两个对角 {@code x1,y1,z1 x2,y2,z2}。 */
    public static String box(BoundingBox box) {
        return box.minX() + "," + box.minY() + "," + box.minZ() + " " + box.maxX() + "," + box.maxY() + ","
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
}
