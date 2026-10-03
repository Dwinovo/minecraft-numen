package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.core.scan.BlockGroups;
import com.dwinovo.numen.core.scan.BlockScan;
import com.dwinovo.numen.core.scan.BlockSearch;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code numen.scan.blocks} 的实现(登记在 {@link com.dwinovo.numen.core.tools.perception.ScanCommands})。看是按刻分片的
 * ({@link BlockScan}),回执在看完的那一刻经这次调用的回信口送出。
 *
 * <p>结果是一串团(Cluster):相连的命中格(对角也算)成一团,由近及远;每团带着它的每一格(方块与位置,近的在前)、最近的那一格与格数。
 * 只是看,什么也不存:要再用,程序就拿着这份结果,或再看一次。
 */
public final class ScanOps {

    private static final int MIN_RADIUS = 1;
    /** 回执那句话里列出的团数:最近的这几团;全部都在返回的数据里。 */
    private static final int SHOWN = 8;

    /** 一团。 */
    public static final ScriptType.Class CLUSTER = new ScriptType.Class("Cluster",
            "Touching blocks a scan found (diagonals count): every block, nearest first.", null, List.of(
            ScriptType.field("blocks", ScriptType.listOf(Shapes.BLOCK.type()), "Every block of it, nearest first."),
            ScriptType.field("nearest", Shapes.BLOCK.type(), "The block nearest to where you stood."),
            ScriptType.field("count", ScriptType.INTEGER, "How many blocks.")));

    private ScanOps() {}

    /** 看一次,回执在看完时送出;返回的数据是全部的团。 */
    public static void scanBlocks(ServerSource src, int radius, List<String> blockIds) {
        NumenPlayer self = src.companion();
        int r = Math.clamp(radius, MIN_RADIUS, BlockScan.MAX_RADIUS);
        Set<Block> targets = ToolParse.parseBlocks(blockIds);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("no valid block ids provided");
        }
        BlockScan.start(self, r, targets, found -> src.reply(listed(found, r)));
    }

    /**
     * What the scan actually covered, in the model's words — {@code null} when it
     * covered everything asked for. A cluster list on its own can't distinguish "no
     * iron within 192 blocks" from "most of that sphere was never looked at", and
     * the model will read the first meaning into silence every time.
     */
    static String coverageNote(BlockSearch.ScanResult res) {
        List<String> notes = new ArrayList<>(3);
        String capped = res.sectionCapNote();
        if (capped != null) {
            notes.add(capped);
        }
        if (res.collectCapHit()) {
            notes.add("stopped at " + BlockSearch.MAX_COLLECT + " matching blocks — only the part nearest you "
                    + "was read and clusters at its edge may be cut off; scan a smaller radius");
        }
        if (res.columnsUnloaded() > 0) {
            notes.add(res.columnsUnloaded() + " of " + res.columnsTotal() + " chunk columns in this "
                    + "radius are not loaded, so they were not searched — blocks out there are "
                    + "UNKNOWN, not absent; walk that way and scan again to find out");
        }
        return notes.isEmpty() ? null : String.join("; ", notes);
    }

    /**
     * 一次看的回执:那句话说在哪、多远、找到几团,最近几团各一行(格数、方块、最近一格在哪多远);没看全时说只是读到的那部分里,并说清
     * 哪里没读到。数据是全部的团({@link #CLUSTER}),由近及远。
     */
    private static String listed(BlockScan.Found found, int radius) {
        List<BlockGroups.Group> all = found.groups();
        JsonArray clusters = new JsonArray();
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            BlockGroups.Group group = all.get(i);
            clusters.add(clusterJson(group));
            if (i < SHOWN) {
                rows.add(summary(group, found.center()));
            }
        }
        BlockSearch.ScanResult res = found.coverage();
        String note = coverageNote(res);
        String center = cell(found.center());
        String where = res.coveredEverything()
                ? " within " + radius + " blocks of " + center
                : " in the part of the " + radius + "-block radius around " + center + " that was read";
        StringBuilder said = new StringBuilder(all.isEmpty() ? "No cluster" + where + "."
                : all.size() + " cluster(s)" + where + ", nearest first" + (all.size() > SHOWN ? " (the nearest "
                + SHOWN + " here; all of them in what it returns)" : "") + ":");
        rows.forEach(row -> said.append("\n").append(row));
        if (note != null) {
            said.append("\nNote: ").append(note);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clusters", clusters);
        return TaskResult.ok(said.toString(), data).toJson();
    }

    /** 一团的数据:每一格是一个 Block(近的在前),最近的那一格,格数。 */
    static JsonObject clusterJson(BlockGroups.Group group) {
        JsonArray blocks = new JsonArray();
        group.cells().forEach((pos, state) -> blocks.add(Shapes.block(pos, state)));
        JsonObject o = new JsonObject();
        o.add("blocks", blocks);
        o.add("nearest", Shapes.block(group.nearest(), group.cells().get(group.nearest())));
        o.addProperty("count", group.cells().size());
        return o;
    }

    /** 回执里一团的那一行:{@code 3 × iron_ore, 1 × deepslate_iron_ore; nearest 12,-40,5, 6 blocks away}。 */
    private static String summary(BlockGroups.Group group, BlockPos center) {
        Map<Block, Integer> kinds = new LinkedHashMap<>();
        for (BlockState state : group.cells().values()) {
            kinds.merge(state.getBlock(), 1, Integer::sum);
        }
        List<String> counted = new ArrayList<>();
        kinds.forEach((block, n) -> counted.add(n + " × " + BuiltInRegistries.BLOCK.getKey(block).getPath()));
        return "- " + String.join(", ", counted) + "; nearest " + cell(group.nearest()) + ", "
                + Math.round(Math.sqrt(group.nearest().distSqr(center))) + " blocks away";
    }

    /** 一格在回执里的写法:{@code 12,-40,5}。 */
    static String cell(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
