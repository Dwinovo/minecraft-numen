package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw NumenTool): look up how to make an item (JEI-style). */
public final class LookupRecipeTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final QueryExtraTools impl = new QueryExtraTools();

    private record Args(String item_id) {}

    @Override
    public String name() {
        return "lookup_recipe";
    }

    @Override
    public String description() {
        return "Look up how to make an item — like JEI. Returns every recipe whose output is this "
                + "item, across all stations: crafting (with the grid layout), smelting / blasting / "
                + "smoking, stonecutting, and smithing — each tagged [crafting] / [smelting] / "
                + "[stonecutter] / [smithing] / …. Then make it: [crafting] → call craft, which lays "
                + "out the grid for you; [smelting] → open the furnace and transfer the input + fuel; "
                + "[stonecutter] / [smithing] → open the station and transfer the inputs. No recipe "
                + "found = the item is mined or traded, not made.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced output item, e.g. minecraft:diamond_pickaxe.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.lookupRecipe(a.item_id(), self));
    }
}
