package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Async query tool (raw NumenTool): bulk-find blocks within a radius. The scan is
 * budget-sliced across server ticks, so it replies later through the callback —
 * the engine just waits for complete() (here driven by the reply).
 */
public final class ScanBlocksTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final ScanTools impl = new ScanTools();

    private record Args(int radius, List<String> block_ids) {}

    @Override
    public String name() {
        return "scan_blocks";
    }

    @Override
    public String description() {
        return "Bulk find blocks of given type(s) within a spherical radius around you. Returns matches "
                + "sorted by distance, capped at 32. A perception tool for surveying the area — to actually "
                + "gather blocks use auto_mine, which finds and digs them itself. Radius is in blocks (max "
                + "192 = 12 chunks — use big radii for landscape features like water, lava lakes or villages' "
                + "blocks; a big scan answers after a few seconds, you can keep acting meanwhile). FLUIDS are "
                + "scannable too: minecraft:water / minecraft:lava work as block_ids, and fluid matches carry "
                + "source:true/false — only SOURCE lava turns to obsidian under water, and only SOURCE water "
                + "fills a bucket. block_ids accepts one or more namespaced ids; include all variants (e.g. "
                + "both iron_ore and deepslate_iron_ore for iron).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("radius", "Spherical search radius in blocks (max 192).", 1, 192)
                .stringArray("block_ids", "List of namespaced block ids to search for.", 1)
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        impl.scanBlocks(a.radius(), a.block_ids(), self, reply);   // replies later via the callback
    }
}
