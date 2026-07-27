package com.dwinovo.numen.core.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** Bounded entity discovery for explicit nearby-combat requests. */
public final class CombatEntityScanner {
    private static final int RESULT_LIMIT = 20;

    private CombatEntityScanner() {
    }

    public static String scan(
        double requestedRadius,
        String requestedFilter,
        String requestedOrigin,
        String requestedLevelScope,
        NumenPlayer player
    ) {
        double radius = Math.clamp(requestedRadius, 1.0, 64.0);
        String filter = parseFilter(requestedFilter);
        CombatArea.Origin origin = CombatArea.Origin.parse(requestedOrigin);
        CombatArea.LevelScope levelScope = CombatArea.LevelScope.parse(requestedLevelScope);
        ServerPlayer center = resolveCenter(player, origin);
        if (center == null) {
            return error("owner is unavailable or is in another dimension; no entities were scanned");
        }

        CombatArea area = levelScope == CombatArea.LevelScope.ALL
            ? CombatArea.allHeights(center.getX(), center.getY(), center.getZ(), radius, origin)
            : CombatArea.samePlane(center.getX(), center.getY(), center.getZ(), radius, origin);
        ServerLevel level = player.level();
        AABB candidatesBox = new AABB(
            area.centerX() - radius,
            area.centerY() - radius,
            area.centerZ() - radius,
            area.centerX() + radius,
            area.centerY() + radius,
            area.centerZ() + radius
        );

        List<ScoredEntity> inScope = new ArrayList<>();
        int excludedByScope = 0;
        for (Entity entity : level.getEntities(player, candidatesBox)) {
            String category = categorise(entity);
            if (!matches(filter, category) || !withinHorizontalRadius(area, entity)) {
                continue;
            }
            if (!area.contains(entity.getX(), entity.getY(), entity.getZ())) {
                excludedByScope++;
                continue;
            }
            inScope.add(new ScoredEntity(entity, category, distance(area, entity)));
        }
        inScope.sort(Comparator.comparingDouble(ScoredEntity::distance));

        JsonArray entities = new JsonArray();
        List<Integer> returnedIds = new ArrayList<>();
        int resultCount = Math.min(inScope.size(), RESULT_LIMIT);
        for (int index = 0; index < resultCount; index++) {
            ScoredEntity scored = inScope.get(index);
            entities.add(describe(scored));
            returnedIds.add(scored.entity().getId());
        }
        CombatAreaRegistry.remember(player, area, returnedIds);

        JsonObject result = new JsonObject();
        result.add("entities", entities);
        result.addProperty("total_found", inScope.size());
        result.addProperty("truncated", inScope.size() > RESULT_LIMIT);
        result.addProperty("excluded_by_scope", excludedByScope);
        result.addProperty("radius_searched", radius);
        result.addProperty("filter", filter);
        result.add("combat_area", describe(area));
        return result.toString();
    }

    private static ServerPlayer resolveCenter(NumenPlayer player, CombatArea.Origin origin) {
        if (origin == CombatArea.Origin.SELF) {
            return player;
        }
        ServerPlayer owner = player.resolveOwnerPlayer();
        return owner != null && owner.level() == player.level() ? owner : null;
    }

    private static String parseFilter(String requested) {
        String filter = requested == null || requested.isBlank()
            ? "hostile"
            : requested.trim().toLowerCase(Locale.ROOT);
        return switch (filter) {
            case "hostile", "passive", "player", "all" -> filter;
            default -> throw new IllegalArgumentException("type_filter must be hostile, passive, player, or all");
        };
    }

    private static boolean matches(String filter, String category) {
        return "all".equals(filter) || filter.equals(category);
    }

    private static String categorise(Entity entity) {
        if (entity instanceof Player) {
            return "player";
        }
        return entity instanceof Monster ? "hostile" : "passive";
    }

    private static boolean withinHorizontalRadius(CombatArea area, Entity entity) {
        double dx = entity.getX() - area.centerX();
        double dz = entity.getZ() - area.centerZ();
        return (dx * dx) + (dz * dz) <= area.horizontalRadius() * area.horizontalRadius();
    }

    private static double distance(CombatArea area, Entity entity) {
        double dx = entity.getX() - area.centerX();
        double dy = entity.getY() - area.centerY();
        double dz = entity.getZ() - area.centerZ();
        return Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
    }

    private static JsonObject describe(ScoredEntity scored) {
        Entity entity = scored.entity();
        JsonObject result = new JsonObject();
        result.addProperty("id", entity.getId());
        result.addProperty("type", entity.getType().getDescriptionId());
        result.addProperty("category", scored.category());

        JsonObject position = new JsonObject();
        position.addProperty("x", entity.getX());
        position.addProperty("y", entity.getY());
        position.addProperty("z", entity.getZ());
        result.add("position", position);
        result.addProperty("distance", scored.distance());
        if (entity instanceof LivingEntity living) {
            result.addProperty("hp", living.getHealth());
            result.addProperty("max_hp", living.getMaxHealth());
        }
        return result;
    }

    public static JsonObject describe(CombatArea area) {
        JsonObject result = new JsonObject();
        result.addProperty("origin", area.origin().name().toLowerCase(Locale.ROOT));
        result.addProperty("level_scope", area.levelScope().name().toLowerCase(Locale.ROOT));
        result.addProperty("horizontal_radius", area.horizontalRadius());
        result.addProperty("vertical_range", area.verticalRange());
        result.addProperty("vertical_axis", "Y");

        JsonObject center = new JsonObject();
        center.addProperty("x", area.centerX());
        center.addProperty("y", area.centerY());
        center.addProperty("z", area.centerZ());
        result.add("center", center);
        return result;
    }

    private static String error(String message) {
        JsonObject result = new JsonObject();
        result.add("entities", new JsonArray());
        result.addProperty("total_found", 0);
        result.addProperty("truncated", false);
        result.addProperty("error", message);
        return result.toString();
    }

    private record ScoredEntity(Entity entity, String category, double distance) {
    }
}
