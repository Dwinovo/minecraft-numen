package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.task.TaskRecord;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code attack} 的进度账本:请求的实体 id、每个 id 的终态(打倒/丢失/够不着)与出手次数。
 *
 * <p>近战与远程曾是两个工具、两份账本,差别只在措辞("defeated/hits" 对 "destroyed/shots"),
 * 为此有三个抽象的词汇钩子。现在只有一个工具,措辞也就只有一套,钩子跟着消失。
 */
public final class AttackTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "attack";

    public final List<Integer> entityIds;

    /**
     * 不点名,打退附近所有敌对生物。
     *
     * <p>按 id 授权在会分裂的怪面前根本行不通:打一只大史莱姆,它裂成四只<b>全新 id</b> 的
     * 小史莱姆,原来那份清单当场作废,任务判"目标丢失"收工,而她还站在史莱姆堆里。
     */
    public final boolean indiscriminate;

    private final Set<Integer> defeated = new LinkedHashSet<>();
    private final Set<Integer> lost = new LinkedHashSet<>();
    private final Set<Integer> unreachable = new LinkedHashSet<>();
    private final Map<Integer, Integer> strikesByEntity = new LinkedHashMap<>();
    private int strikes;

    public AttackTaskRecord(String toolCallId, long deadlineGameTime,
                            List<Integer> entityIds, boolean indiscriminate) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.entityIds = List.copyOf(entityIds);
        this.indiscriminate = indiscriminate;
    }

    public Set<Integer> defeated() { return Set.copyOf(defeated); }
    public Set<Integer> lost() { return Set.copyOf(lost); }
    public Set<Integer> unreachable() { return Set.copyOf(unreachable); }
    public int strikes() { return strikes; }

    public void defeated(int id) { defeated.add(id); }
    public void lost(int id) { lost.add(id); }
    public void unreachable(int id) { unreachable.add(id); }

    /** 出手一次(挥击或射出一箭)。 */
    public void strike(int id) {
        strikes++;
        strikesByEntity.merge(id, 1, Integer::sum);
    }

    public int strikes(int id) { return strikesByEntity.getOrDefault(id, 0); }

    public String status(int id) {
        if (defeated.contains(id)) return "defeated";
        if (lost.contains(id)) return "lost";
        if (unreachable.contains(id)) return "unreachable";
        return "pending";
    }

    public boolean terminal(int id) {
        return defeated.contains(id) || lost.contains(id) || unreachable.contains(id);
    }

    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    @Override
    public String describe() {
        return indiscriminate
                ? "清场 已放倒 " + defeated.size()
                : "战斗 " + defeated.size() + "/" + entityIds.size();
    }
}
