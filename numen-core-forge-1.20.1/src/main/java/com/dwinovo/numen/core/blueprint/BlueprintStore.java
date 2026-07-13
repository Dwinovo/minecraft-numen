package com.dwinovo.numen.core.blueprint;

import com.dwinovo.numen.core.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.dwinovo.numen.util.SafeJsonStore;

/** Captures and persists blueprints under {@code <world>/numen/blueprints}. */
public final class BlueprintStore {

    public static final int MAX_VOLUME = 32_768;
    public static final int MAX_AXIS = 64;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final java.util.regex.Pattern SAFE_NAME =
            java.util.regex.Pattern.compile("[\\p{L}\\p{N}_-]{1,48}");

    private BlueprintStore() {}

    public static Blueprint capture(ServerLevel level, String rawName, BlockPos first, BlockPos second) {
        String name = validateName(rawName);
        int minX = Math.min(first.getX(), second.getX());
        int minY = Math.min(first.getY(), second.getY());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxX = Math.max(first.getX(), second.getX());
        int maxY = Math.max(first.getY(), second.getY());
        int maxZ = Math.max(first.getZ(), second.getZ());
        int sx = maxX - minX + 1;
        int sy = maxY - minY + 1;
        int sz = maxZ - minZ + 1;
        if (minY < level.getMinBuildHeight() || maxY >= level.getMaxBuildHeight()) {
            throw new IllegalArgumentException("blueprint region exceeds build height ["
                    + level.getMinBuildHeight() + "," + (level.getMaxBuildHeight() - 1) + "]");
        }
        long volume = (long) sx * sy * sz;
        if (sx > MAX_AXIS || sy > MAX_AXIS || sz > MAX_AXIS || volume > MAX_VOLUME) {
            throw new IllegalArgumentException("blueprint region is " + sx + "x" + sy + "x" + sz
                    + " (" + volume + " cells); max axis=" + MAX_AXIS + ", max volume=" + MAX_VOLUME);
        }

        List<Blueprint.BlockEntry> entries = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    cursor.set(x, y, z);
                    if (!level.hasChunkAt(cursor)) {
                        throw new IllegalArgumentException("blueprint region contains an unloaded chunk at "
                                + cursor.getX() + "," + cursor.getY() + "," + cursor.getZ());
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    if (id == null) continue;
                    entries.add(new Blueprint.BlockEntry(
                            x - minX, y - minY, z - minZ, id.toString(), properties(state)));
                }
            }
        }
        return new Blueprint(Blueprint.CURRENT_SCHEMA, name,
                level.dimension().location().toString(), sx, sy, sz,
                Instant.now().toEpochMilli(), entries);
    }

    public static void save(MinecraftServer server, java.util.UUID ownerUuid, Blueprint blueprint) {
        Path file = file(server, ownerUuid, blueprint.name());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            SafeJsonStore.write(file, GSON.toJson(blueprint), value -> {
                Blueprint parsed = GSON.fromJson(value, Blueprint.class);
                validate(parsed, blueprint.name());
                return parsed;
            });
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw new IllegalStateException("failed to save blueprint '" + blueprint.name() + "'", e);
        }
    }

    public static Blueprint load(MinecraftServer server, java.util.UUID ownerUuid, String rawName) {
        String name = validateName(rawName);
        Path file = file(server, ownerUuid, name);
        if (!Files.isRegularFile(file) && !Files.isRegularFile(SafeJsonStore.backup(file))) {
            throw new IllegalArgumentException("unknown blueprint: " + name);
        }
        try {
            var stored = SafeJsonStore.read(file, value -> GSON.fromJson(value, Blueprint.class));
            if (stored.value().isEmpty()) throw new IllegalArgumentException("unknown blueprint: " + name);
            if (stored.recoveredFromBackup()) Constants.LOG.warn("Recovered blueprint {} from backup", file);
            Blueprint blueprint = stored.value().orElseThrow();
            validate(blueprint, name);
            return blueprint;
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Failed to read blueprint {}", file, e);
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalArgumentException("invalid blueprint '" + name + "': " + e.getMessage(), e);
        }
    }

    public static BlockState decodeState(Blueprint.BlockEntry entry) {
        ResourceLocation id = ResourceLocation.tryParse(entry.blockId());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("blueprint references unknown block: " + entry.blockId());
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == Blocks.AIR) throw new IllegalArgumentException("blueprint contains an air entry");
        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> value : entry.properties().entrySet()) {
            if ("waterlogged".equals(value.getKey())) continue;
            Property<?> property = block.getStateDefinition().getProperty(value.getKey());
            if (property == null) continue; // tolerate a property removed by a mod update
            state = setProperty(state, property, value.getValue());
        }
        return state;
    }

    public static String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "blueprint name must be 1-48 letters/digits and may contain '_' or '-'");
        }
        return name;
    }

    private static Path file(MinecraftServer server, java.util.UUID ownerUuid, String rawName) {
        if (ownerUuid == null) throw new IllegalArgumentException("companion has no owner");
        String name = validateName(rawName);
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("numen").resolve("blueprints")
                .resolve(ownerUuid.toString());
        Path file = dir.resolve(name + ".json").normalize();
        if (!file.startsWith(dir.normalize())) throw new IllegalArgumentException("invalid blueprint path");
        return file;
    }

    private static void validate(Blueprint blueprint, String expectedName) {
        if (blueprint == null) throw new IllegalArgumentException("empty blueprint file");
        if (blueprint.schema() != Blueprint.CURRENT_SCHEMA) {
            throw new IllegalArgumentException("unsupported blueprint schema: " + blueprint.schema());
        }
        if (!expectedName.equals(blueprint.name())) throw new IllegalArgumentException("blueprint name mismatch");
        long volume = blueprint.volume();
        if (blueprint.sizeX() < 1 || blueprint.sizeY() < 1 || blueprint.sizeZ() < 1
                || blueprint.sizeX() > MAX_AXIS || blueprint.sizeY() > MAX_AXIS
                || blueprint.sizeZ() > MAX_AXIS || volume > MAX_VOLUME) {
            throw new IllegalArgumentException("invalid blueprint dimensions");
        }
        if (blueprint.blocks().size() > volume) throw new IllegalArgumentException("too many block entries");
        java.util.HashSet<String> occupied = new java.util.HashSet<>();
        for (Blueprint.BlockEntry entry : blueprint.blocks()) {
            if (entry.x() < 0 || entry.x() >= blueprint.sizeX()
                    || entry.y() < 0 || entry.y() >= blueprint.sizeY()
                    || entry.z() < 0 || entry.z() >= blueprint.sizeZ()) {
                throw new IllegalArgumentException("blueprint block lies outside dimensions");
            }
            String cell = entry.x() + "," + entry.y() + "," + entry.z();
            if (!occupied.add(cell)) throw new IllegalArgumentException("duplicate blueprint cell: " + cell);
            decodeState(entry);
        }
    }

    private static Map<String, String> properties(BlockState state) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) {
            if ("waterlogged".equals(property.getName())) continue;
            out.put(property.getName(), propertyName(state, property));
        }
        return out;
    }

    private static <T extends Comparable<T>> String propertyName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static <T extends Comparable<T>> BlockState setProperty(
            BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }
}
