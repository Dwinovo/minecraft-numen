package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.task.MeleeAttackTaskRecord;
import com.dwinovo.numen.core.task.RangedAttackTaskRecord;
import com.dwinovo.numen.task.TaskRecord;

import java.util.List;

/** Creates the typed records for melee and ranged combat tasks. */
public final class CombatTools {

    public TaskRecord meleeAttack(List<Integer> entityIds, ToolContext ctx) {
        List<Integer> ids = normalizeEntityIds(entityIds);
        long timeout = Math.min(10L * 60L * 20L,
                Math.max(120L * 20L, ids.size() * 60L * 20L));
        return new MeleeAttackTaskRecord(ctx.toolCallId(), ctx.deadline(timeout), ids);
    }

    public TaskRecord rangedAttack(List<Integer> entityIds, ToolContext ctx) {
        List<Integer> ids = normalizeEntityIds(entityIds);
        long timeout = Math.min(10L * 60L * 20L,
                Math.max(120L * 20L, ids.size() * 75L * 20L));
        return new RangedAttackTaskRecord(ctx.toolCallId(), ctx.deadline(timeout), ids);
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
}
