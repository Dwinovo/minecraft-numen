package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.dispatchAsync;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Build a bounded set of explicit block cells as one background task. */
public final class BuildTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_BLOCKS = 512;
    private static final long MIN_TIMEOUT_TICKS = 60 * 20;
    private static final long TICKS_PER_BLOCK = 20 * 20;

    private record Args(List<BlockSpec> blocks, Boolean replace_existing, Integer layer_height) {}
    private record BlockSpec(String block_id, int x, int y, int z,
                             String facing, String axis, String half,
                             Map<String, String> properties) {}

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String description() {
        return "Place or clear explicit block cells from inventory as one background task — one cell or "
                + "many (1-512). Pass absolute cells in `blocks`; each has `block_id`, `x`, `y`, `z`, and optional "
                + "state fields (`facing`, `axis`, `half`, `properties`). A cell whose `block_id` is `minecraft:air` "
                + "CLEARS/breaks whatever is there (drops harvest normally). The task walks, climbs and bridges to "
                + "each cell, clears wrong blocks at requested cells when replacement is enabled, and can build in "
                + "optional vertical layers. Use this for a single block, a correction, or portals, frames, walls, "
                + "stairs, pillars, roofs and larger construction. It does not read schematic files yet. BACKGROUND: "
                + "after acceptance, wait for task_finished and never resend the same cell list while it is running or after status=done.";
    }


    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("block_id", Map.of(
                "type", "string",
                "description", "Namespaced id of the block item to place, e.g. minecraft:obsidian. "
                        + "Use minecraft:air to clear/break whatever occupies the cell."));
        props.put("x", Map.of("type", "integer", "description", "Target x."));
        props.put("y", Map.of("type", "integer", "description", "Target y."));
        props.put("z", Map.of("type", "integer", "description", "Target z."));
        props.put("facing", enumSchema("Optional facing: north/south/east/west/up/down.",
                "north", "south", "east", "west", "up", "down"));
        props.put("axis", enumSchema("Optional pillar/log axis.", "x", "y", "z"));
        props.put("half", enumSchema("Optional slab/stair half.", "top", "bottom"));
        props.put("properties", Map.of(
                "type", List.of("object", "null"),
                "description", "Optional block-state properties by name, e.g. {\"open\":\"false\"}.",
                "additionalProperties", Map.of("type", "string")));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", props);
        item.put("required", List.of("block_id", "x", "y", "z"));
        item.put("additionalProperties", false);

        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("type", "array");
        blocks.put("description", "Explicit block cells to construct, in absolute world coordinates.");
        blocks.put("items", item);
        blocks.put("minItems", 1);
        blocks.put("maxItems", MAX_BLOCKS);

        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("blocks", blocks);
        rootProps.put("replace_existing", Map.of(
                "type", "boolean",
                "description", "Optional, default true. Clear wrong non-protected blocks at requested cells."));
        rootProps.put("layer_height", Map.of(
                "type", "integer",
                "description", "Optional. Explicit vertical layer step (1-16) builds bottom-up in slices of"
                        + " that height. Default 0 = auto: structures taller than 2 blocks build in 1-block"
                        + " layers (the body always works from atop the finished part); flat or low"
                        + " structures place freely."));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", rootProps);
        root.put("required", List.of("blocks"));
        root.put("additionalProperties", false);
        return root;
    }

    private static Map<String, Object> enumSchema(String description, String... values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", List.of("string", "null"));
        schema.put("description", description);
        schema.put("enum", valuesWithNull(values));
        return schema;
    }

    private static List<Object> valuesWithNull(String... values) {
        List<Object> out = new ArrayList<>();
        for (String value : values) {
            out.add(value);
        }
        out.add(null);
        return out;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        List<BuildTaskRecord.Target> targets = parseTargets(parsed.blocks());
        boolean replaceExisting = parsed.replace_existing() == null || parsed.replace_existing();
        int layerHeight = parsed.layer_height() == null ? 0 : parsed.layer_height();
        if (layerHeight < 0 || layerHeight > 16) {
            throw new IllegalArgumentException("layer_height must be between 0 and 16");
        }
        long timeout = Math.max(MIN_TIMEOUT_TICKS, (long) targets.size() * TICKS_PER_BLOCK);
        dispatchAsync(companion, new BuildTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(timeout), targets, replaceExisting, layerHeight), reply);
    }

    static List<BuildTaskRecord.Target> parseTargets(List<BlockSpec> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("blocks must contain at least one cell");
        }
        if (blocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("build accepts at most " + MAX_BLOCKS + " cells");
        }
        List<BuildTaskRecord.Target> targets = new ArrayList<>(blocks.size());
        Set<BlockPos> seen = new LinkedHashSet<>();
        for (BlockSpec spec : blocks) {
            if (spec == null || spec.block_id() == null || spec.block_id().isBlank()) {
                throw new IllegalArgumentException("each block needs block_id, x, y and z");
            }
            Item item = ToolArgs.parseItem(spec.block_id());
            net.minecraft.world.level.block.Block block;
            if (item == Items.AIR) {
                block = Blocks.AIR;
            } else if (item instanceof BlockItem blockItem) {
                block = blockItem.getBlock();
            } else {
                throw new IllegalArgumentException(spec.block_id() + " is not a placeable block");
            }
            BlockPos pos = new BlockPos(spec.x(), spec.y(), spec.z());
            if (!seen.add(pos)) {
                throw new IllegalArgumentException("duplicate build cell " + pos.toShortString());
            }
            Direction facing = opt(spec.facing()) == null ? null : Direction.byName(opt(spec.facing()));
            if (opt(spec.facing()) != null && facing == null) {
                throw new IllegalArgumentException("invalid facing: " + spec.facing());
            }
            Direction.Axis axis = opt(spec.axis()) == null ? null : Direction.Axis.byName(opt(spec.axis()));
            if (opt(spec.axis()) != null && axis == null) {
                throw new IllegalArgumentException("invalid axis: " + spec.axis());
            }
            String half = opt(spec.half());
            if (half != null && !half.equals("top") && !half.equals("bottom")) {
                throw new IllegalArgumentException("invalid half: " + spec.half());
            }
            String label = BuiltInRegistries.BLOCK.getKey(block).getPath();
            Boolean topHalf = half == null ? null : half.equals("top");
            BlockState desiredState = applyProperties(
                    new BuildTaskRecord.Target(block, item, pos, label,
                            facing, axis, topHalf).desiredState(), spec.properties());
            targets.add(new BuildTaskRecord.Target(desiredState, item, pos, label,
                    facing, axis, topHalf));
        }
        return List.copyOf(targets);
    }


    private static BlockState applyProperties(BlockState state, Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return state;
        }
        BlockState out = state;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String name = opt(entry.getKey());
            String value = opt(entry.getValue());
            if (name == null || value == null) {
                throw new IllegalArgumentException("block-state properties need non-empty names and values");
            }
            Property<?> property = out.getBlock().getStateDefinition().getProperty(name);
            if (property == null) {
                throw new IllegalArgumentException(out.getBlock().getName().getString()
                        + " has no property " + name);
            }
            out = setProperty(out, property, value);
        }
        return out;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setProperty(BlockState state, Property property, String value) {
        java.util.Optional parsed = property.getValue(value);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("invalid value " + value
                    + " for property " + property.getName());
        }
        return state.setValue(property, (Comparable) parsed.get());
    }

    private static String opt(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
