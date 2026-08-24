package com.dwinovo.numen.core.task.inventory;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.FailureType;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@code drop_items} on the player body — toss items forward, natively. One-tick. */
public final class DropCompanionTask extends AbstractCompanionTask<DropItemsTaskRecord> {

    private int dropped;
    private String doneMessage = "done";

    public DropCompanionTask(NumenPlayer player, DropItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(
                () -> player.level() instanceof ServerLevel ? null
                        : new Precondition.Failure("not on a server level", FailureType.UNKNOWN),
                () -> PlayerInv.count(player.getInventory(), r.item) > 0 ? null
                        : new Precondition.Failure("no " + r.label + " in inventory to drop",
                                FailureType.NO_MATERIAL));
    }

    @Override
    protected void onStart() {
        Inventory inv = player.getInventory();
        int have = PlayerInv.count(inv, r.item);
        dropped = Math.min(r.count, have);

        // 丢的是背包里<b>真实的那几叠</b>:从格子里拆出来的栈带着自己的全部组件
        // (附魔/耐久/改名/容器内容物)。曾经按数量销毁再 new ItemStack 重造,附魔镐
        // 丢出来变白板——凭空重造只属于创造模式的 take_items,不属于这里。
        // Toss like a real player: native Player.drop(stack, false) throws each stack in the facing
        // direction with vanilla motion + pickup delay and fires the drop event (mods watching item
        // tosses see it) — instead of hand-building an ItemEntity with a made-up velocity.
        int remaining = dropped;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !s.is(r.item)) continue;
            ItemStack out = inv.removeItem(i, Math.min(s.getCount(), remaining));
            remaining -= out.getCount();
            player.drop(out, false);
        }
        inv.setChanged();
        doneMessage = "dropped " + dropped + "x " + r.label
                + (dropped < r.count ? " (only had " + dropped + ")" : "");
        succeed();   // work is done — finalize this same tick, before a Stop can mislabel it
    }

    @Override
    protected TaskState onTick() {
        return TaskState.SUCCESS;
    }

    /** No nav / overlay to release. */
    @Override
    protected void cleanup() {}

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("dropped", dropped);
        data.put("remaining_in_inventory", PlayerInv.count(player.getInventory(), r.item));
        return data;
    }

    @Override
    protected String successMessage() {
        return doneMessage;
    }

    @Override
    protected String timeoutMessage() {
        return "drop timed out unexpectedly";
    }

    @Override
    protected String cancelledMessage() {
        return "drop interrupted";
    }
}
