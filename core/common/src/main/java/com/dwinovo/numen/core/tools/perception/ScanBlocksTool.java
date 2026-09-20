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
 * Async query tool (raw NumenTool): find blocks within a radius, reported as groups. The
 * scan is budget-sliced across server ticks, so it replies later through the callback —
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
        return "Find blocks of given type(s) near you, reported as GROUPS: matching cells that touch (diagonals "
                + "count) and get the same permission answer for breaking them — so a player's log pillar "
                + "standing against a wild tree comes back as two groups. Nearest groups first, up to 16; "
                + "groups_total counts them all when the whole radius was read. Each group gives: id, cells "
                + "and a count per block type, the nearest cell with direction and distance, a box "
                + "(x1,y1,z1..x2,y2,z2 — the form avoid_break takes), permission for breaking its cells "
                + "(allow; ask = mine asks the owner first; deny = mine stops) with the reason, sources = "
                + "source cells for water or lava (a source behaves very differently from flowing), and for "
                + "groups of up to 16 cells every position. A very large group comes back cut along 16-block "
                + "section lines, one group per piece. Group ids (g1, g2, ...) are valid only until your "
                + "next scan_blocks; to dig exactly those cells, pass them to mine as groups. Sees terrain "
                + "that is loaded right now; anything further out is UNKNOWN, not empty, and note says when "
                + "that happened — walk that way and scan again. Give every variant of what you want, e.g. "
                + "both iron_ore and deepslate_iron_ore.";
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
