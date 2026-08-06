package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.task.combat.AttackTaskRecord;
import com.dwinovo.numen.task.TaskRecord;

import java.util.List;

/** 造 {@code attack} 的任务账本。 */
public final class CombatOps {

    /** 每个目标给多久;总时长封顶十分钟。 */
    private static final long PER_TARGET_TICKS = 75L * 20L;
    private static final long MIN_TICKS = 120L * 20L;
    private static final long MAX_TICKS = 10L * 60L * 20L;

    /**
     * 不给 id 就是<b>无差别</b>:打退附近所有敌对生物。会分裂的怪(史莱姆、岩浆怪)只能这么打
     * ——它一裂开,点名的那份 id 清单就作废了。
     */
    public TaskRecord attack(List<Integer> entityIds, ToolContext ctx) {
        boolean indiscriminate = entityIds == null || entityIds.isEmpty();
        List<Integer> ids = indiscriminate ? List.of() : normalizeEntityIds(entityIds);
        long count = indiscriminate ? 4 : ids.size();
        long timeout = Math.min(MAX_TICKS, Math.max(MIN_TICKS, count * PER_TARGET_TICKS));
        return new AttackTaskRecord(ctx.toolCallId(), ctx.deadline(timeout), ids, indiscriminate);
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
