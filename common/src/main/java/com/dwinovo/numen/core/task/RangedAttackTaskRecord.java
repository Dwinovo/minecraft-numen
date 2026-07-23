package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskRecord;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed descriptor for attacking explicit runtime entity ids at range. */
public final class RangedAttackTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "ranged_attack";

    public final List<Integer> entityIds;

    private final Set<Integer> destroyed = new LinkedHashSet<>();
    private final Set<Integer> lost = new LinkedHashSet<>();
    private final Set<Integer> unreachable = new LinkedHashSet<>();
    private final Map<Integer, Integer> shotsByEntity = new LinkedHashMap<>();
    private int shots;

    public RangedAttackTaskRecord(String toolCallId, long deadlineGameTime, List<Integer> entityIds) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.entityIds = List.copyOf(entityIds);
    }

    public Set<Integer> destroyed() { return Set.copyOf(destroyed); }
    public Set<Integer> lost() { return Set.copyOf(lost); }
    public Set<Integer> unreachable() { return Set.copyOf(unreachable); }
    public int shots() { return shots; }
    public void destroyed(int id) { destroyed.add(id); }
    public void lost(int id) { lost.add(id); }
    public void unreachable(int id) { unreachable.add(id); }

    public void shot(int id) {
        shots++;
        shotsByEntity.merge(id, 1, Integer::sum);
    }

    public int shots(int id) { return shotsByEntity.getOrDefault(id, 0); }

    public String status(int id) {
        if (destroyed.contains(id)) return "destroyed";
        if (lost.contains(id)) return "lost";
        if (unreachable.contains(id)) return "unreachable";
        return "pending";
    }

    public boolean terminal(int id) {
        return destroyed.contains(id) || lost.contains(id) || unreachable.contains(id);
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + destroyed.size() + "/" + entityIds.size();
    }
}
