package com.dwinovo.numen.core.blueprint;

import com.dwinovo.numen.core.task.PlayerInv;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rotates a blueprint and compares it with the target world/inventory. */
public final class BlueprintPlanner {

    private static final int PREVIEW_LIMIT = 12;
    private static final int MATERIAL_TYPE_LIMIT = 16;

    private BlueprintPlanner() {}

    public enum Turn {
        DEG_0("0", Rotation.NONE),
        DEG_90("90", Rotation.CLOCKWISE_90),
        DEG_180("180", Rotation.CLOCKWISE_180),
        DEG_270("270", Rotation.COUNTERCLOCKWISE_90);

        private final String id;
        private final Rotation rotation;

        Turn(String id, Rotation rotation) {
            this.id = id;
            this.rotation = rotation;
        }

        public String id() { return id; }
        Rotation vanilla() { return rotation; }

        public static Turn parse(String raw) {
            String normalized = raw == null || raw.isBlank() ? "0" : raw.trim();
            for (Turn turn : values()) if (turn.id.equals(normalized)) return turn;
            throw new IllegalArgumentException("rotation must be one of 0, 90, 180, 270");
        }
    }

    public record Target(BlockPos pos, BlockState state, boolean secondary) {}
    public record Placement(BlockPos pos, BlockState state, Item item, int itemCount) {}
    public record StateFix(BlockPos pos, BlockState state, boolean secondary) {}
    public record Conflict(BlockPos pos, String currentBlock, String wantedBlock) {}

    public record Plan(
            Blueprint blueprint,
            BlockPos anchor,
            Turn turn,
            int rotatedSizeX,
            int rotatedSizeZ,
            int alreadyCorrect,
            List<Placement> placements,
            List<StateFix> stateFixes,
            List<Conflict> conflicts,
            Map<Item, Integer> required,
            Map<Item, Integer> available,
            Map<Item, Integer> missing,
            Map<String, Integer> unplaceable) {

        public Plan {
            placements = List.copyOf(placements);
            stateFixes = List.copyOf(stateFixes);
            conflicts = List.copyOf(conflicts);
            required = Map.copyOf(required);
            available = Map.copyOf(available);
            missing = Map.copyOf(missing);
            unplaceable = Map.copyOf(unplaceable);
        }

        public boolean buildable(boolean creative) {
            return conflicts.isEmpty()
                    && (creative || (stateFixes.isEmpty() && unplaceable.isEmpty() && missing.isEmpty()));
        }

        public int pendingChanges() {
            int unsupported = unplaceable.values().stream().mapToInt(Integer::intValue).sum();
            return placements.size() + stateFixes.size() + unsupported;
        }

        public JsonObject toJson(boolean creative) {
            JsonObject root = new JsonObject();
            root.addProperty("blueprint", blueprint.name());
            root.addProperty("source_dimension", blueprint.sourceDimension());
            root.addProperty("anchor_x", anchor.getX());
            root.addProperty("anchor_y", anchor.getY());
            root.addProperty("anchor_z", anchor.getZ());
            root.addProperty("rotation", turn.id());
            root.addProperty("size_x", rotatedSizeX);
            root.addProperty("size_y", blueprint.sizeY());
            root.addProperty("size_z", rotatedSizeZ);
            root.addProperty("saved_non_air_blocks", blueprint.blocks().size());
            root.addProperty("already_correct", alreadyCorrect);
            root.addProperty("blocks_to_place", placements.size());
            root.addProperty("state_fixes", stateFixes.size());
            root.addProperty("conflicts", conflicts.size());
            root.addProperty("buildable_now", buildable(creative));
            root.addProperty("creative_mode", creative);
            root.add("materials_required", itemMap(required));
            root.add("materials_available", itemMap(available));
            root.add("materials_missing", itemMap(missing));
            if (required.size() > MATERIAL_TYPE_LIMIT || available.size() > MATERIAL_TYPE_LIMIT
                    || missing.size() > MATERIAL_TYPE_LIMIT) root.addProperty("materials_truncated", true);
            JsonObject noItem = new JsonObject();
            int noItemCount = 0;
            for (Map.Entry<String, Integer> e : unplaceable.entrySet()) {
                if (noItemCount++ >= MATERIAL_TYPE_LIMIT) break;
                noItem.addProperty(e.getKey(), e.getValue());
            }
            root.add("blocks_without_placeable_item", noItem);
            if (unplaceable.size() > MATERIAL_TYPE_LIMIT) {
                root.addProperty("blocks_without_placeable_item_truncated", true);
            }
            root.add("conflict_preview", conflictPreview(conflicts));
            root.add("placement_preview", placementPreview(placements));
            if (conflicts.size() > PREVIEW_LIMIT || placements.size() > PREVIEW_LIMIT) {
                root.addProperty("preview_truncated", true);
            }
            root.addProperty("tip", conflicts.isEmpty()
                    ? "Use build_blueprint with the same anchor/rotation after gathering materials."
                    : "Clear or move the listed conflicts, then run plan_blueprint again.");
            return root;
        }
    }

    public static List<Target> targets(Blueprint blueprint, BlockPos anchor, Turn turn) {
        List<Target> out = new ArrayList<>(blueprint.blocks().size());
        for (Blueprint.BlockEntry entry : blueprint.blocks()) {
            LocalPos local = rotate(entry.x(), entry.z(), blueprint.sizeX(), blueprint.sizeZ(), turn);
            BlockState state = BlueprintStore.decodeState(entry).rotate(turn.vanilla());
            out.add(new Target(anchor.offset(local.x, entry.y(), local.z).immutable(),
                    state, isSecondaryPart(state)));
        }
        out.sort(Comparator.comparing(Target::pos, Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ)
                .thenComparingInt(BlockPos::getX)));
        return out;
    }

    public static Plan plan(Blueprint blueprint, NumenPlayer player, BlockPos anchor, Turn turn) {
        List<Placement> placements = new ArrayList<>();
        List<StateFix> fixes = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        Map<Item, Integer> required = new LinkedHashMap<>();
        Map<String, Integer> unplaceable = new LinkedHashMap<>();
        int correct = 0;

        for (Blueprint.BlockEntry entry : blueprint.blocks()) {
            LocalPos local = rotate(entry.x(), entry.z(), blueprint.sizeX(), blueprint.sizeZ(), turn);
            BlockPos target = anchor.offset(local.x, entry.y(), local.z);
            if (target.getY() < player.level.getMinBuildHeight()
                    || target.getY() >= player.level.getMaxBuildHeight()) {
                conflicts.add(new Conflict(target.immutable(), "outside_build_height", entry.blockId()));
                continue;
            }
            if (!player.level.hasChunkAt(target)) {
                conflicts.add(new Conflict(target.immutable(), "unloaded_chunk", entry.blockId()));
                continue;
            }
            BlockState wanted = BlueprintStore.decodeState(entry).rotate(turn.vanilla());
            boolean secondary = isSecondaryPart(wanted);
            BlockState current = player.level.getBlockState(target);
            if (current.equals(wanted)) {
                correct++;
                continue;
            }
            if (current.is(wanted.getBlock())) {
                if (!player.isCreative() && survivalPlacementStateMatches(current, wanted)) correct++;
                else fixes.add(new StateFix(target.immutable(), wanted, secondary));
                continue;
            }
            if (!current.isAir() && !current.canBeReplaced()) {
                conflicts.add(new Conflict(target.immutable(), blockId(current), blockId(wanted)));
                continue;
            }
            if (secondary) {
                fixes.add(new StateFix(target.immutable(), wanted, true));
                // Doors, beds and tall plants create this half when their primary half is placed.
                // Keeping it as a state-fix lets creative restore it exactly and makes survival
                // validate that the native primary placement generated it.
                continue;
            }
            Item item = wanted.getBlock().asItem();
            if (item == Items.AIR) {
                unplaceable.merge(blockId(wanted), 1, Integer::sum);
                continue;
            }
            int itemCount = placementItemCount(wanted);
            if (itemCount < 0) {
                unplaceable.merge(blockId(wanted), 1, Integer::sum);
                continue;
            }
            placements.add(new Placement(target.immutable(), wanted, item, itemCount));
            required.merge(item, itemCount, Integer::sum);
        }

        java.util.HashSet<BlockPos> placementCells = new java.util.HashSet<>();
        for (Placement placement : placements) placementCells.add(placement.pos());
        fixes.removeIf(fix -> fix.secondary()
                && placementCells.contains(primaryPos(fix.pos(), fix.state()))
                && player.level.getBlockState(fix.pos()).canBeReplaced());

        Comparator<BlockPos> byBuildOrder = Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ)
                .thenComparingInt(BlockPos::getX);
        placements.sort(Comparator.comparing(Placement::pos, byBuildOrder));
        fixes.sort(Comparator.comparing(StateFix::pos, byBuildOrder));

        Map<Item, Integer> available = new LinkedHashMap<>();
        Map<Item, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : required.entrySet()) {
            int have = PlayerInv.count(player.getInventory(), e.getKey());
            available.put(e.getKey(), have);
            int shortage = e.getValue() - have;
            if (shortage > 0) missing.put(e.getKey(), shortage);
        }

        int rx = turn == Turn.DEG_90 || turn == Turn.DEG_270
                ? blueprint.sizeZ() : blueprint.sizeX();
        int rz = turn == Turn.DEG_90 || turn == Turn.DEG_270
                ? blueprint.sizeX() : blueprint.sizeZ();
        return new Plan(blueprint, anchor.immutable(), turn, rx, rz, correct,
                placements, fixes, conflicts, required, available, missing, unplaceable);
    }

    /**
     * Compare only state that a real placement can intentionally control. Dynamic neighbour/redstone
     * properties are deliberately ignored, otherwise a wall connection or power level would make an
     * otherwise correct survival build permanently unfinishable.
     */
    public static boolean survivalPlacementStateMatches(BlockState actual, BlockState wanted) {
        if (actual.getBlock() != wanted.getBlock()) return false;
        if (wanted.hasProperty(BlockStateProperties.FACING)
                && actual.getValue(BlockStateProperties.FACING) != wanted.getValue(BlockStateProperties.FACING)) return false;
        if (wanted.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                && actual.getValue(BlockStateProperties.HORIZONTAL_FACING) != wanted.getValue(BlockStateProperties.HORIZONTAL_FACING)) return false;
        if (wanted.hasProperty(BlockStateProperties.AXIS)
                && actual.getValue(BlockStateProperties.AXIS) != wanted.getValue(BlockStateProperties.AXIS)) return false;
        if (wanted.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)
                && actual.getValue(BlockStateProperties.HORIZONTAL_AXIS) != wanted.getValue(BlockStateProperties.HORIZONTAL_AXIS)) return false;
        if (wanted.hasProperty(BlockStateProperties.HALF)
                && actual.getValue(BlockStateProperties.HALF) != wanted.getValue(BlockStateProperties.HALF)) return false;
        if (wanted.hasProperty(BlockStateProperties.SLAB_TYPE)
                && actual.getValue(BlockStateProperties.SLAB_TYPE) != wanted.getValue(BlockStateProperties.SLAB_TYPE)) return false;
        if (wanted.hasProperty(BlockStateProperties.BED_PART)
                && actual.getValue(BlockStateProperties.BED_PART) != wanted.getValue(BlockStateProperties.BED_PART)) return false;
        if (wanted.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && actual.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != wanted.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)) return false;
        for (Property<?> property : wanted.getProperties()) {
            String name = property.getName();
            if (("layers".equals(name) || "candles".equals(name) || "pickles".equals(name)
                    || "eggs".equals(name) || "flower_amount".equals(name) || "bites".equals(name))
                    && !sameProperty(actual, wanted, property)) return false;
        }
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean sameProperty(BlockState actual, BlockState wanted, Property<?> property) {
        Property raw = property;
        return actual.hasProperty(raw) && actual.getValue(raw).equals(wanted.getValue(raw));
    }

    /** Number of item uses required for one saved block; -1 means unsupported in survival. */
    private static int placementItemCount(BlockState state) {
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return -1;
        // Layer/count blocks need repeated uses at the same cell; the current native place task
        // intentionally places one block per record, so report them as unsupported instead of
        // under-counting materials or silently producing the wrong state.
        for (Property<?> p : state.getProperties()) {
            String n = p.getName();
            if (("layers".equals(n) || "candles".equals(n) || "pickles".equals(n)
                    || "eggs".equals(n) || "flower_amount".equals(n))
                    && integerValue(state, p) > 1) return -1;
            if ("bites".equals(n) && integerValue(state, p) > 0) return -1;
        }
        return 1;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int integerValue(BlockState state, Property<?> property) {
        Comparable value = state.getValue((Property) property);
        return value instanceof Integer i ? i : 0;
    }

    private static BlockPos primaryPos(BlockPos secondary, BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return secondary.below();
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)) {
            net.minecraft.core.Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return secondary.relative(facing.getOpposite());
        }
        return secondary;
    }

    private static boolean isSecondaryPart(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)) {
            return state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
        }
        return false;
    }

    private static LocalPos rotate(int x, int z, int sizeX, int sizeZ, Turn turn) {
        return switch (turn) {
            case DEG_0 -> new LocalPos(x, z);
            case DEG_90 -> new LocalPos(sizeZ - 1 - z, x);
            case DEG_180 -> new LocalPos(sizeX - 1 - x, sizeZ - 1 - z);
            case DEG_270 -> new LocalPos(z, sizeX - 1 - x);
        };
    }

    private static JsonObject itemMap(Map<Item, Integer> map) {
        JsonObject out = new JsonObject();
        int count = 0;
        for (Map.Entry<Item, Integer> e : map.entrySet()) {
            if (count++ >= MATERIAL_TYPE_LIMIT) break;
            out.addProperty(itemId(e.getKey()), e.getValue());
        }
        return out;
    }

    private static JsonArray conflictPreview(List<Conflict> conflicts) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < Math.min(PREVIEW_LIMIT, conflicts.size()); i++) {
            Conflict c = conflicts.get(i);
            JsonObject o = position(c.pos());
            o.addProperty("current", c.currentBlock());
            o.addProperty("wanted", c.wantedBlock());
            out.add(o);
        }
        return out;
    }

    private static JsonArray placementPreview(List<Placement> placements) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < Math.min(PREVIEW_LIMIT, placements.size()); i++) {
            Placement p = placements.get(i);
            JsonObject o = position(p.pos());
            o.addProperty("block", blockId(p.state()));
            out.add(o);
        }
        return out;
    }

    private static JsonObject position(BlockPos pos) {
        JsonObject o = new JsonObject();
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private record LocalPos(int x, int z) {}
}
