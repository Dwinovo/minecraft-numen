package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.blueprint.BlueprintPlanner;
import com.dwinovo.numen.core.tools.CraftingPlanner;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Explicit, versioned codecs for restart-safe records; never serialises runtime executors. */
final class TaskRecordCodecRegistry {

    private interface Codec<R extends TaskRecord> {
        JsonObject encode(R record);
        R decode(String toolCallId, long deadline, JsonObject params, JsonObject progress);
    }

    private record Entry<R extends TaskRecord>(Class<R> type, Codec<R> codec) {}

    private static final Map<String, Entry<?>> BY_NAME = new LinkedHashMap<>();
    private static final Map<Class<? extends TaskRecord>, Entry<?>> BY_TYPE = new LinkedHashMap<>();

    static {
        register(MoveToTaskRecord.TOOL_NAME, MoveToTaskRecord.class, new Codec<>() {
            public JsonObject encode(MoveToTaskRecord r) {
                JsonObject o = new JsonObject();
                nullable(o, "x", r.x); nullable(o, "y", r.y); nullable(o, "z", r.z);
                o.addProperty("speed", r.speed);
                return o;
            }
            public MoveToTaskRecord decode(String id, long d, JsonObject o, JsonObject p) {
                return new MoveToTaskRecord(id, d, optDouble(o, "x"), optDouble(o, "y"),
                        optDouble(o, "z"), doubleValue(o, "speed", 1.0));
            }
        });
        register(PlaceBlockTaskRecord.TOOL_NAME, PlaceBlockTaskRecord.class, new Codec<>() {
            public JsonObject encode(PlaceBlockTaskRecord r) {
                JsonObject o = pos(r.pos);
                o.addProperty("item", itemId(r.item));
                if (r.facing != null) o.addProperty("facing", r.facing.getName());
                if (r.axis != null) o.addProperty("axis", r.axis.getName());
                if (r.topHalf != null) o.addProperty("top_half", r.topHalf);
                return o;
            }
            public PlaceBlockTaskRecord decode(String id, long d, JsonObject o, JsonObject p) {
                Item item = item(o, "item");
                if (!(item instanceof BlockItem bi)) throw new IllegalArgumentException("item is not a block item");
                Direction facing = o.has("facing") ? Direction.byName(o.get("facing").getAsString()) : null;
                Direction.Axis axis = o.has("axis") ? Direction.Axis.byName(o.get("axis").getAsString()) : null;
                Boolean top = o.has("top_half") ? o.get("top_half").getAsBoolean() : null;
                return new PlaceBlockTaskRecord(id, d, bi.getBlock(), item, blockPos(o),
                        BuiltInRegistries.ITEM.getKey(item).getPath(), facing, axis, top);
            }
        });
        register(BreakBlockTaskRecord.TOOL_NAME, BreakBlockTaskRecord.class, new Codec<>() {
            public JsonObject encode(BreakBlockTaskRecord r) { return pos(r.target); }
            public BreakBlockTaskRecord decode(String id, long d, JsonObject o, JsonObject p) {
                return new BreakBlockTaskRecord(id, d, blockPos(o));
            }
        });
        register(BuildBlueprintTaskRecord.TOOL_NAME, BuildBlueprintTaskRecord.class, new Codec<>() {
            public JsonObject encode(BuildBlueprintTaskRecord r) {
                JsonObject o = pos(r.anchor);
                o.addProperty("name", r.blueprintName);
                o.addProperty("rotation", r.turn.id());
                o.addProperty("creative", r.creative);
                o.addProperty("batch_limit", r.batchLimit);
                JsonObject protectedItems = new JsonObject();
                r.protectedItems.forEach(protectedItems::addProperty);
                o.add("protected_items", protectedItems);
                return o;
            }
            public BuildBlueprintTaskRecord decode(String id, long d, JsonObject o, JsonObject p) {
                int batch = Math.max(1, Math.min(8192, intValue(o, "batch_limit", 64)));
                BuildBlueprintTaskRecord r = new BuildBlueprintTaskRecord(id, d,
                        string(o, "name"), blockPos(o), BlueprintPlanner.Turn.parse(string(o, "rotation")),
                        bool(o, "creative", false), batch, intMap(o, "protected_items"));
                r.restoreProgress(intValue(p, "changed", 0), intValue(p, "skipped", 0));
                return r;
            }
        });
        register(MineBlockTaskRecord.TOOL_NAME, MineBlockTaskRecord.class, new Codec<>() {
            public JsonObject encode(MineBlockTaskRecord r) {
                JsonObject o = new JsonObject();
                JsonArray ids = new JsonArray();
                for (Block b : r.targets) ids.add(blockId(b));
                o.add("blocks", ids);
                o.addProperty("count", r.count);
                o.addProperty("radius", r.maxRadius);
                o.addProperty("label", r.label);
                return o;
            }
            public MineBlockTaskRecord decode(String id, long d, JsonObject o, JsonObject p) {
                Set<Block> blocks = new LinkedHashSet<>();
                for (var el : o.getAsJsonArray("blocks")) blocks.add(block(el.getAsString()));
                if (blocks.isEmpty()) throw new IllegalArgumentException("empty mining target set");
                return new MineBlockTaskRecord(id, d, blocks,
                        Math.max(1, Math.min(256, intValue(o, "count", 1))),
                        Math.max(1, Math.min(96, intValue(o, "radius", 48))), string(o, "label"), intValue(p, "mined", 0),
                        intValue(p, "inventory_baseline", -1));
            }
        });
        register(CollectItemsTaskRecord.TOOL_NAME, CollectItemsTaskRecord.class, new Codec<>() {
            public JsonObject encode(CollectItemsTaskRecord r) {
                JsonObject o = new JsonObject();
                JsonArray ids = new JsonArray();
                for (Item item : r.filter) ids.add(itemId(item));
                o.add("items", ids);
                o.addProperty("radius", r.radius);
                o.addProperty("label", r.label);
                return o;
            }
            public CollectItemsTaskRecord decode(String id, long d, JsonObject o, JsonObject p) {
                Set<Item> items = new LinkedHashSet<>();
                for (var el : o.getAsJsonArray("items")) items.add(item(el.getAsString()));
                return new CollectItemsTaskRecord(id, d, items,
                        Math.max(1, Math.min(48, intValue(o, "radius", 16))),
                        string(o, "label"), intValue(p, "collected", 0));
            }
        });
        register(LocateBiomeTaskRecord.TOOL_NAME, LocateBiomeTaskRecord.class, new Codec<>() {
            public JsonObject encode(LocateBiomeTaskRecord r) { return property("biome", r.biome); }
            public LocateBiomeTaskRecord decode(String id, long d, JsonObject o, JsonObject p) {
                return new LocateBiomeTaskRecord(id, d, string(o, "biome"));
            }
        });
        register(LocateStructureTaskRecord.TOOL_NAME, LocateStructureTaskRecord.class, new Codec<>() {
            public JsonObject encode(LocateStructureTaskRecord r) { return property("structure", r.structure); }
            public LocateStructureTaskRecord decode(String id, long d, JsonObject o, JsonObject p) {
                return new LocateStructureTaskRecord(id, d, string(o, "structure"));
            }
        });
        register(CraftItemsTaskRecord.TOOL_NAME, CraftItemsTaskRecord.class, new Codec<>() {
            public JsonObject encode(CraftItemsTaskRecord r) {
                JsonObject o = new JsonObject();
                o.addProperty("target", itemId(r.target));
                o.addProperty("count", r.count);
                o.addProperty("max_depth", r.maxDepth);
                JsonArray steps = new JsonArray();
                for (CraftingPlanner.Step step : r.steps) {
                    JsonObject s = new JsonObject();
                    s.addProperty("recipe_id", step.recipeId().toString());
                    s.addProperty("station", step.station().id());
                    s.addProperty("detail", step.detail());
                    s.addProperty("output", itemId(step.output()));
                    s.addProperty("output_count", step.outputCount());
                    s.addProperty("batches", step.batches());
                    JsonArray ingredients = new JsonArray();
                    for (Item ingredient : step.ingredientSlots()) {
                        ingredients.add(ingredient == Items.AIR ? "minecraft:air" : itemId(ingredient));
                    }
                    s.add("ingredients", ingredients);
                    steps.add(s);
                }
                o.add("steps", steps);
                return o;
            }
            public CraftItemsTaskRecord decode(String id, long d, JsonObject o, JsonObject p) {
                java.util.ArrayList<CraftingPlanner.Step> steps = new java.util.ArrayList<>();
                for (var el : o.getAsJsonArray("steps")) {
                    JsonObject s = el.getAsJsonObject();
                    java.util.ArrayList<Item> ingredients = new java.util.ArrayList<>();
                    for (var ingredient : s.getAsJsonArray("ingredients")) {
                        String raw = ingredient.getAsString();
                        ingredients.add("minecraft:air".equals(raw) ? Items.AIR : item(raw));
                    }
                    ResourceLocation recipeId = ResourceLocation.tryParse(string(s, "recipe_id"));
                    if (recipeId == null) throw new IllegalArgumentException("invalid persisted recipe id");
                    steps.add(new CraftingPlanner.Step(
                            recipeId,
                            CraftingPlanner.Station.parse(string(s, "station")),
                            string(s, "detail"), item(s, "output"),
                            intValue(s, "output_count", 1), intValue(s, "batches", 1), ingredients));
                }
                CraftItemsTaskRecord r = new CraftItemsTaskRecord(id, d, item(o, "target"),
                        intValue(o, "count", 1), intValue(o, "max_depth", 4), steps);
                r.setBaseline(intValue(p, "baseline", 0));
                r.setProduced(intValue(p, "produced", 0));
                r.setProgress(intValue(p, "step_index", 0), intValue(p, "batch_index", 0));
                CraftItemsTaskRecord.Phase phase = CraftItemsTaskRecord.Phase.valueOf(
                        p.has("phase") ? p.get("phase").getAsString() : "READY");
                BlockPos station = p.has("station_x") ? new BlockPos(
                        p.get("station_x").getAsInt(), p.get("station_y").getAsInt(), p.get("station_z").getAsInt()) : null;
                r.checkpoint(phase, station, intValue(p, "action_output_baseline", 0),
                        intValue(p, "machine_input_baseline", 0), intValue(p, "machine_output_baseline", 0));
                java.util.LinkedHashMap<Item, Integer> baselines = new java.util.LinkedHashMap<>();
                if (p.has("action_input_baselines") && p.get("action_input_baselines").isJsonObject()) {
                    for (var e : p.getAsJsonObject("action_input_baselines").entrySet()) {
                        baselines.put(item(e.getKey()), e.getValue().getAsInt());
                    }
                }
                r.setActionInputBaselines(baselines);
                if (p.has("machine_fuel")) r.setMachineFuel(item(p, "machine_fuel"));
                return r;
            }
        });
    }

    private TaskRecordCodecRegistry() {}

    static boolean isRecoverableTool(String toolName) { return BY_NAME.containsKey(toolName); }

    static JsonObject encode(TaskRecord record) {
        Entry<?> entry = BY_TYPE.get(record.getClass());
        if (entry == null) return null;
        @SuppressWarnings("unchecked") Entry<TaskRecord> typed = (Entry<TaskRecord>) entry;
        JsonObject o = typed.codec().encode(record);
        JsonObject progress = new JsonObject();
        if (record instanceof MineBlockTaskRecord mine) {
            progress.addProperty("mined", mine.getMined());
            progress.addProperty("inventory_baseline", mine.getInventoryBaseline());
        }
        if (record instanceof CollectItemsTaskRecord collect) progress.addProperty("collected", collect.getCollected());
        if (record instanceof BuildBlueprintTaskRecord build) {
            progress.addProperty("changed", build.getChanged());
            progress.addProperty("skipped", build.getSkipped());
        }
        if (record instanceof CraftItemsTaskRecord craft) {
            progress.addProperty("baseline", craft.getBaseline());
            progress.addProperty("produced", craft.getProduced());
            progress.addProperty("step_index", craft.getStepIndex());
            progress.addProperty("batch_index", craft.getBatchIndex());
            progress.addProperty("phase", craft.getPhase().name());
            progress.addProperty("action_output_baseline", craft.getActionOutputBaseline());
            progress.addProperty("machine_input_baseline", craft.getMachineInputBaseline());
            progress.addProperty("machine_output_baseline", craft.getMachineOutputBaseline());
            JsonObject inputBaselines = new JsonObject();
            for (var baselineEntry : craft.getActionInputBaselines().entrySet()) {
                inputBaselines.addProperty(itemId(baselineEntry.getKey()), baselineEntry.getValue());
            }
            progress.add("action_input_baselines", inputBaselines);
            if (craft.getMachineFuel() != null) {
                progress.addProperty("machine_fuel", itemId(craft.getMachineFuel()));
            }
            if (craft.getStationPos() != null) {
                progress.addProperty("station_x", craft.getStationPos().getX());
                progress.addProperty("station_y", craft.getStationPos().getY());
                progress.addProperty("station_z", craft.getStationPos().getZ());
            }
        }
        JsonObject out = new JsonObject();
        out.add("parameters", o);
        out.add("progress", progress);
        return out;
    }

    static TaskRecord decode(String type, String toolCallId, long deadline,
                             JsonObject parameters, JsonObject progress, String argumentsJson) {
        Entry<?> entry = BY_NAME.get(type);
        if (entry == null) throw new IllegalArgumentException("unknown recoverable task type: " + type);
        @SuppressWarnings("unchecked") Entry<TaskRecord> typed = (Entry<TaskRecord>) entry;
        TaskRecord record = typed.codec().decode(toolCallId, deadline, parameters, progress);
        record.setArgumentsJson(argumentsJson);
        return record;
    }

    private static <R extends TaskRecord> void register(String name, Class<R> type, Codec<R> codec) {
        Entry<R> entry = new Entry<>(type, codec);
        BY_NAME.put(name, entry); BY_TYPE.put(type, entry);
    }

    private static JsonObject pos(BlockPos pos) {
        JsonObject o = new JsonObject(); o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY()); o.addProperty("z", pos.getZ()); return o;
    }
    private static BlockPos blockPos(JsonObject o) {
        return new BlockPos(intValue(o, "x", 0), intValue(o, "y", 0), intValue(o, "z", 0));
    }
    private static JsonObject property(String key, String value) { JsonObject o = new JsonObject(); o.addProperty(key, value); return o; }
    private static void nullable(JsonObject o, String key, Double value) { if (value != null) o.addProperty(key, value); }
    private static Double optDouble(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : null; }
    private static int intValue(JsonObject o, String key, int fallback) { return o != null && o.has(key) ? o.get(key).getAsInt() : fallback; }
    private static Map<String, Integer> intMap(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonObject()) return Map.of();
        java.util.LinkedHashMap<String, Integer> out = new java.util.LinkedHashMap<>();
        for (var entry : o.getAsJsonObject(key).entrySet()) out.put(entry.getKey(), entry.getValue().getAsInt());
        return out;
    }
    private static double doubleValue(JsonObject o, String key, double fallback) { return o.has(key) ? o.get(key).getAsDouble() : fallback; }
    private static boolean bool(JsonObject o, String key, boolean fallback) { return o.has(key) ? o.get(key).getAsBoolean() : fallback; }
    private static String string(JsonObject o, String key) { if (!o.has(key)) throw new IllegalArgumentException("missing " + key); return o.get(key).getAsString(); }
    private static String blockId(Block block) { return BuiltInRegistries.BLOCK.getKey(block).toString(); }
    private static String itemId(Item item) { return BuiltInRegistries.ITEM.getKey(item).toString(); }
    private static Block block(String raw) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("unknown block: " + raw);
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == Blocks.AIR) throw new IllegalArgumentException("air is not a task target");
        return block;
    }
    private static Item item(JsonObject o, String key) { return item(string(o, key)); }
    private static Item item(String raw) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) throw new IllegalArgumentException("unknown item: " + raw);
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) throw new IllegalArgumentException("air is not an item");
        return item;
    }
}
