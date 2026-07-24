package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskRecord;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed descriptor for attacking an explicit, perception-authorized entity-id set. */
public final class MeleeAttackTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "melee_attack";
    public final List<Integer> entityIds;
    private final Set<Integer> defeated = new LinkedHashSet<>();
    private final Set<Integer> lost = new LinkedHashSet<>();
    private final Set<Integer> unreachable = new LinkedHashSet<>();
    private final Map<Integer, Integer> hitsByEntity = new LinkedHashMap<>();
    private int hits;

    public MeleeAttackTaskRecord(String toolCallId, long deadlineGameTime, List<Integer> entityIds) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.entityIds = List.copyOf(entityIds);
    }

    public Set<Integer> defeated() { return Set.copyOf(defeated); }
    public Set<Integer> lost() { return Set.copyOf(lost); }
    public Set<Integer> unreachable() { return Set.copyOf(unreachable); }
    public int hits() { return hits; }
    public void defeated(int id) { defeated.add(id); }
    public void lost(int id) { lost.add(id); }
    public void unreachable(int id) { unreachable.add(id); }

    public void hit(int id) {
        hits++;
        hitsByEntity.merge(id, 1, Integer::sum);
    }

    public int hits(int id) { return hitsByEntity.getOrDefault(id, 0); }

    public String status(int id) {
        if (defeated.contains(id)) return "defeated";
        if (lost.contains(id)) return "lost";
        if (unreachable.contains(id)) return "unreachable";
        return "pending";
    }

    public boolean terminal(int id) {
        return defeated.contains(id) || lost.contains(id) || unreachable.contains(id);
    }

    @Override public String describe() {
        return TOOL_NAME + " " + defeated.size() + "/" + entityIds.size();
    }
}
