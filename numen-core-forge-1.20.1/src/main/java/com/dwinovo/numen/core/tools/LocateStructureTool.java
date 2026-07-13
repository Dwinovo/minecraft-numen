package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): locate the nearest structure of a type. */
public final class LocateStructureTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final LocateTools impl = new LocateTools();

    private record Args(String structure) {}

    @Override
    public String name() {
        return "locate_structure";
    }

    @Override
    public String description() {
        return "Find the nearest structure of a given type and get its coordinates, compass direction and "
                + "distance. `structure` is a structure id — minecraft:stronghold, minecraft:fortress, "
                + "minecraft:bastion_remnant, minecraft:ancient_city, minecraft:end_city, minecraft:monument, "
                + "minecraft:mansion, minecraft:pillager_outpost — or a #tag for families like "
                + "#minecraft:village or #minecraft:ruined_portal. Searches YOUR CURRENT dimension only: "
                + "fortresses/bastions exist in the Nether, end cities in the End. For the stronghold this "
                + "is the eye-free equivalent of throwing eyes of ender — save the eyes for the 12 portal "
                + "frames. The returned y is approximate; navigate by x/z and scan_blocks when you arrive.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("structure", "Structure id (e.g. minecraft:fortress) or #tag (e.g. #minecraft:village).")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.locateStructure(a.structure(), ctx(toolCallId, companion)));
    }
}
