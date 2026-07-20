package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.task.MeleeAttackTaskRecord;
import com.dwinovo.numen.core.task.ShootTaskRecord;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Creates the typed records for melee and ranged combat tasks. */
public final class CombatTools {

    private static final int MAX_COUNT = 64;
    private static final long TICKS_PER_KILL = 30 * 20;
    private static final long MIN_TIMEOUT_TICKS = 60 * 20;
    private static final int SHOOT_DEFAULT_MAX_RADIUS = 64;
    private static final int SHOOT_MAX_ALLOWED_RADIUS = 128;

    public TaskRecord meleeAttack(List<Integer> entityIds, ToolContext ctx) {
        List<Integer> ids = normalizeEntityIds(entityIds);
        long timeout = Math.min(10L * 60L * 20L,
                Math.max(120L * 20L, ids.size() * 60L * 20L));
        return new MeleeAttackTaskRecord(ctx.toolCallId(), ctx.deadline(timeout), ids);
    }

    static List<Integer> normalizeEntityIds(List<Integer> entityIds) {
        if (entityIds == null) throw new IllegalArgumentException("entity_ids is required");
        List<Integer> ids = entityIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("entity_ids contained no runtime entity ids");
        }
        if (ids.size() > 20) {
            throw new IllegalArgumentException("entity_ids accepts at most 20 distinct ids");
        }
        return ids;
    }

    public TaskRecord shoot(List<String> entityIds, int count, Integer radius, ToolContext ctx) {
        Set<EntityType<?>> targets = readEntityIdsShoot(entityIds);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("entity_ids contained no valid entity type ids");
        }
        count = Math.clamp(count, 1, MAX_COUNT);

        int resolvedRadius = radius == null ? SHOOT_DEFAULT_MAX_RADIUS
                : Math.clamp(radius, 1, SHOOT_MAX_ALLOWED_RADIUS);
        String label = labelFor(targets);
        long timeout = Math.max(MIN_TIMEOUT_TICKS, (long) count * TICKS_PER_KILL);
        return new ShootTaskRecord(ctx.toolCallId(), ctx.deadline(timeout), targets,
                count, resolvedRadius, label);
    }

    private static Set<EntityType<?>> readEntityIdsShoot(List<String> entityIds) {
        Set<EntityType<?>> out = new LinkedHashSet<>();
        for (String value : entityIds) {
            if (value == null) continue;
            ResourceLocation id = ResourceLocation.tryParse(value);
            if (id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                out.add(BuiltInRegistries.ENTITY_TYPE.get(id));
            }
        }
        return out;
    }

    private static String labelFor(Set<EntityType<?>> targets) {
        EntityType<?> first = targets.iterator().next();
        String path = BuiltInRegistries.ENTITY_TYPE.getKey(first).getPath();
        return targets.size() == 1 ? path : path + "+" + (targets.size() - 1);
    }
}
