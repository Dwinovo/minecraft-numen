package com.dwinovo.numen.core.blueprint;

import java.util.List;
import java.util.Map;

/**
 * World-persisted blueprint. Coordinates are relative to the minimum corner of
 * the captured region. Only block states are stored: block-entity NBT and
 * inventories are deliberately excluded so a blueprint can never duplicate
 * container contents.
 */
public record Blueprint(
        int schema,
        String name,
        String sourceDimension,
        int sizeX,
        int sizeY,
        int sizeZ,
        long createdAt,
        List<BlockEntry> blocks) {

    public static final int CURRENT_SCHEMA = 1;

    public Blueprint {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public long volume() {
        return (long) sizeX * sizeY * sizeZ;
    }

    /** One non-air block and all serialisable state properties. */
    public record BlockEntry(
            int x,
            int y,
            int z,
            String blockId,
            Map<String, String> properties) {

        public BlockEntry {
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }
}
