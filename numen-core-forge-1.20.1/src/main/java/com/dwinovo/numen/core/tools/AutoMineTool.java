package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): gather blocks by type and quantity. */
public final class AutoMineTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final BlockActionTools impl = new BlockActionTools();

    private record Args(List<String> block_ids, int count, Integer radius) {}

    @Override
    public String name() {
        return "auto_mine";
    }

    @Override
    public String description() {
        return "Gather blocks by type and quantity. Give the block id(s) and how many you want — the "
                + "entity finds the nearest ones and travels to each with full terrain-traversing "
                + "navigation: it digs tunnels to reach buried ores, pillars up to blocks high on cliffs, "
                + "and bridges gaps with cobblestone/dirt from its own inventory, all automatically — then "
                + "mines them and repeats until it has gathered `count` of the resulting ITEMS or none "
                + "remain nearby. count is items, not blocks: a block can drop several (redstone_ore → ~4 "
                + "redstone), so count:10 redstone mines only ~3 ore. It counts only NEW items gained, on "
                + "top of what you already carry. You do NOT provide coordinates, call move_to, or pre-clear "
                + "a path; carrying some cobblestone/dirt helps it cross terrain. Include all variants of a "
                + "resource in block_ids (e.g. iron_ore AND deepslate_iron_ore). Optional radius caps how "
                + "far to look (default auto-expands). Returns the actual number gathered, which may be less "
                + "than requested if the deposit runs out. You must hold a tool that can harvest the target: "
                + "mining a block your main-hand tool can't harvest fails up front and tells you the minimum "
                + "tier required (e.g. iron_ore needs a stone pickaxe). Equip the right pickaxe/axe/shovel "
                + "yourself first (equip_item) — check get_self_status for your main hand.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .stringArray("block_ids", "Namespaced block id(s) to gather; include all variants.", 1)
                .integer("count", "How many ITEMS to gather (not blocks) — a block may drop several, and it "
                        + "counts only items gained on top of what you already hold.", 1, 256)
                .optionalInteger("radius", "Optional max search radius in blocks (default auto-expands to 48).", 1, 96)
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.autoMine(a.block_ids(), a.count(), a.radius(), ctx(toolCallId, companion)));
    }
}
