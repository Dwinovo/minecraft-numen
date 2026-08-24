package com.dwinovo.numen.core.tools.perception;
import com.dwinovo.numen.core.tools.ScanOps;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
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
public final class ScanBlocksTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final ScanOps impl = new ScanOps();

    private record Args(int radius, List<String> block_ids) {}

    @Override
    public String name() {
        return "scan_blocks";
    }

    @Override
    public String description() {
        return "Find blocks of given type(s) near you and report where they are, nearest first, up to 32. "
                + "Surveying only — to actually gather blocks use mine, which finds and digs them itself. "
                + "Sees terrain that is loaded right now; anything further out is UNKNOWN, not empty, and "
                + "note says when that happened — walk that way and scan again. Water and lava are scannable, "
                + "and those matches carry source:true/false (a source cell behaves very differently from "
                + "flowing). Give every variant of what you want, e.g. both iron_ore and deepslate_iron_ore.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("radius", "Spherical search radius in blocks (max 192).", 1, 192)
                .stringArray("block_ids", "List of namespaced block ids to search for.", 1)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        impl.scanBlocks(a.radius(), a.block_ids(), self, reply);   // replies later via the callback
    }
}
