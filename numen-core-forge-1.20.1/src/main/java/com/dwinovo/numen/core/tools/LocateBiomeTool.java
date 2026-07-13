package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): locate the nearest biome of a type. */
public final class LocateBiomeTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final LocateTools impl = new LocateTools();

    private record Args(String biome) {}

    @Override
    public String name() {
        return "locate_biome";
    }

    @Override
    public String description() {
        return "Find the nearest biome of a given type and get its coordinates, compass direction and "
                + "distance — no walking needed. `biome` is a biome id — minecraft:warped_forest (endermen "
                + "for pearls), minecraft:soul_sand_valley, minecraft:desert, minecraft:plains, "
                + "minecraft:dark_forest — or a #tag for families like #minecraft:is_forest or "
                + "#minecraft:is_ocean. Searches YOUR CURRENT dimension only, ~6400 blocks out. Biome edges "
                + "are fuzzy: the answer is accurate to ~64 blocks, so move_to the x/z (pick a sensible y) "
                + "and confirm with scan_blocks or scan_nearby_entities when you arrive.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("biome", "Biome id (e.g. minecraft:warped_forest) or #tag (e.g. #minecraft:is_forest).")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.locateBiome(a.biome(), ctx(toolCallId, companion)));
    }
}
