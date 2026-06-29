package com.dwinovo.numen.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.tasks.BreakBlockCompanionTask;
import com.dwinovo.numen.task.tasks.BreakBlockTaskRecord;
import com.dwinovo.numen.task.tasks.DropCompanionTask;
import com.dwinovo.numen.task.tasks.DropItemsTaskRecord;
import com.dwinovo.numen.task.tasks.EatCompanionTask;
import com.dwinovo.numen.task.tasks.EatItemTaskRecord;
import com.dwinovo.numen.task.tasks.EquipCompanionTask;
import com.dwinovo.numen.task.tasks.HuntCompanionTask;
import com.dwinovo.numen.task.tasks.HuntTaskRecord;
import com.dwinovo.numen.task.tasks.ShootCompanionTask;
import com.dwinovo.numen.task.tasks.ShootTaskRecord;
import com.dwinovo.numen.task.tasks.EquipTaskRecord;
import com.dwinovo.numen.task.tasks.MineBlockTaskRecord;
import com.dwinovo.numen.task.tasks.MineCompanionTask;
import com.dwinovo.numen.task.tasks.MoveToCompanionTask;
import com.dwinovo.numen.task.tasks.MoveToTaskRecord;
import com.dwinovo.numen.task.tasks.PlaceBlockCompanionTask;
import com.dwinovo.numen.task.tasks.PlaceBlockTaskRecord;
import com.dwinovo.numen.task.tasks.WaitCompanionTask;
import com.dwinovo.numen.task.tasks.WaitTaskRecord;

/**
 * Maps a queued {@link TaskRecord} to the {@link CompanionTask} that runs it on
 * the player body — one {@code instanceof} branch per record type, no reflection.
 * Record types with no branch fall back to {@link UnsupportedCompanionTask}.
 */
public final class CompanionTaskFactory {

    private CompanionTaskFactory() {}

    public static CompanionTask create(NumenPlayer player, TaskRecord record) {
        if (record instanceof MoveToTaskRecord r) return new MoveToCompanionTask(player, r);
        if (record instanceof MineBlockTaskRecord r) return new MineCompanionTask(player, r);
        if (record instanceof EquipTaskRecord r) return new EquipCompanionTask(player, r);
        if (record instanceof WaitTaskRecord r) return new WaitCompanionTask(player, r);
        if (record instanceof DropItemsTaskRecord r) return new DropCompanionTask(player, r);
        if (record instanceof BreakBlockTaskRecord r) return new BreakBlockCompanionTask(player, r);
        if (record instanceof EatItemTaskRecord r) return new EatCompanionTask(player, r);
        if (record instanceof HuntTaskRecord r) return new HuntCompanionTask(player, r);
        if (record instanceof ShootTaskRecord r) return new ShootCompanionTask(player, r);
        if (record instanceof com.dwinovo.numen.task.tasks.CollectItemsTaskRecord r)
            return new com.dwinovo.numen.task.tasks.CollectItemsTaskGoal(player, r);
        if (record instanceof PlaceBlockTaskRecord r) return new PlaceBlockCompanionTask(player, r);
        if (record instanceof com.dwinovo.numen.task.tasks.InteractAtTaskRecord r)
            return new com.dwinovo.numen.task.tasks.InteractAtCompanionTask(player, r);
        if (record instanceof com.dwinovo.numen.task.tasks.InteractEntityTaskRecord r)
            return new com.dwinovo.numen.task.tasks.InteractEntityCompanionTask(player, r);
        if (record instanceof com.dwinovo.numen.task.tasks.LocateStructureTaskRecord r)
            return new com.dwinovo.numen.task.tasks.LocateStructureTaskGoal(player, r);
        if (record instanceof com.dwinovo.numen.task.tasks.LocateBiomeTaskRecord r)
            return new com.dwinovo.numen.task.tasks.LocateBiomeTaskGoal(player, r);
        return new UnsupportedCompanionTask(record);
    }
}
