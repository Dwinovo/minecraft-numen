package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class SearchItemsTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_RESULTS = 40;

    private record Args(String query, String mod_id) {}

    @Override
    public String name() {
        return "search_items";
    }

    @Override
    public String description() {
        return "Search all registered items (vanilla + EVERY loaded mod) by keyword. "
                + "Use this to discover modded items when you don't know the exact ID. "
                + "Returns up to " + MAX_RESULTS + " matching item IDs with their display names. "
                + "Optional mod_id filter to search only one mod. "
                + "After finding items, use lookup_recipe to check crafting recipes.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("query", "Search keyword, e.g. \"sword\", \"pickaxe\", \"dark_matter\", \"helmet\". Lowercase, matches anywhere in the item ID or name.")
                .optionalString("mod_id", "Optional: limit search to one mod namespace, e.g. \"projecte\", \"minecraft\", \"create\"")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        String query = a.query().toLowerCase().trim();
        String modFilter = a.mod_id() != null ? a.mod_id().toLowerCase().trim() : null;

        List<Item> matches = new ArrayList<>();

        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) continue;

            if (modFilter != null && !modFilter.isEmpty() && !id.getNamespace().equals(modFilter)) {
                continue;
            }

            String itemId = id.toString().toLowerCase();
            String itemName = item.getDescription().getString().toLowerCase();

            if (itemId.contains(query) || itemName.contains(query)) {
                matches.add(item);
                if (matches.size() >= MAX_RESULTS) break;
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("query", a.query());
        result.addProperty("total_found", matches.size());

        JsonArray items = new JsonArray();
        for (Item item : matches) {
            JsonObject obj = new JsonObject();
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            obj.addProperty("id", id.toString());
            obj.addProperty("name", item.getDescription().getString());
            obj.addProperty("mod", id.getNamespace());
            items.add(obj);
        }
        result.add("items", items);

        if (matches.isEmpty()) {
            result.addProperty("tip", "Try a different keyword. Use broader terms like 'sword' instead of 'diamond_sword'. Omit mod_id to search all mods.");
        } else if (matches.size() >= MAX_RESULTS) {
            result.addProperty("tip", "Results truncated at " + MAX_RESULTS + ". Use more specific keywords or a mod_id filter to narrow down.");
        }

        reply.accept(TaskResult.ok(result.toString()).toJson());
    }
}
