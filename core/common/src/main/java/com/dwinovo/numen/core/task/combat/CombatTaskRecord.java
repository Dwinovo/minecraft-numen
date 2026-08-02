package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.task.TaskRecord;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 战斗类任务共享的进度账本:请求的实体 id 集、每个 id 的终态
 * (完成/丢失/不可达)与打击计数。melee 与 ranged 的记账结构与状态
 * 判定完全同一,只此一份;两者唯一的真实差异是回执用语
 * ("defeated"+"hits" vs "destroyed"+"shots"),由子类的词汇钩子提供,
 * 代码里一律用中性的 completed/strike。
 */
public abstract class CombatTaskRecord extends TaskRecord {

    public final List<Integer> entityIds;

    private final Set<Integer> completed = new LinkedHashSet<>();
    private final Set<Integer> lost = new LinkedHashSet<>();
    private final Set<Integer> unreachable = new LinkedHashSet<>();
    private final Map<Integer, Integer> strikesByEntity = new LinkedHashMap<>();
    private int strikes;

    protected CombatTaskRecord(String toolName, String toolCallId, long deadlineGameTime,
                               List<Integer> entityIds) {
        super(toolName, toolCallId, deadlineGameTime);
        this.entityIds = List.copyOf(entityIds);
    }

    /** 完成态的过去式用语(defeated / destroyed)——status 与回执键名用。 */
    public abstract String completedWord();

    /** 完成态的进行式用语(defeating / destroying)——超时/打断文案用。 */
    public abstract String completingWord();

    /** 打击计数的用语(hits / shots)——回执键名用。 */
    public abstract String strikeWord();

    public Set<Integer> completed() { return Set.copyOf(completed); }
    public Set<Integer> lost() { return Set.copyOf(lost); }
    public Set<Integer> unreachable() { return Set.copyOf(unreachable); }
    public int strikes() { return strikes; }

    public void completed(int id) { completed.add(id); }
    public void lost(int id) { lost.add(id); }
    public void unreachable(int id) { unreachable.add(id); }

    public void strike(int id) {
        strikes++;
        strikesByEntity.merge(id, 1, Integer::sum);
    }

    public int strikes(int id) { return strikesByEntity.getOrDefault(id, 0); }

    public String status(int id) {
        if (completed.contains(id)) return completedWord();
        if (lost.contains(id)) return "lost";
        if (unreachable.contains(id)) return "unreachable";
        return "pending";
    }

    public boolean terminal(int id) {
        return completed.contains(id) || lost.contains(id) || unreachable.contains(id);
    }

    @Override
    public String describe() {
        return getToolName() + " " + completed.size() + "/" + entityIds.size();
    }
}
