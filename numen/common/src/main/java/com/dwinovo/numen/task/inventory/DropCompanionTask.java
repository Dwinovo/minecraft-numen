package com.dwinovo.numen.task.inventory;
import com.dwinovo.numen.api.permission.GatedAct.Permit;
import com.dwinovo.numen.api.permission.GatedAct.PermitState;
import com.dwinovo.numen.FailureType;

import com.dwinovo.numen.api.task.TaskState;

import com.dwinovo.numen.PlayerInv;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.StackLabel;
import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.task.base.AbstractCompanionTask;
import com.dwinovo.numen.task.base.Precondition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code inv drop} on the player body — toss items forward, natively. One tick when the permission
 * layer allows it; otherwise the call waits for the owner's answer.
 */
public final class DropCompanionTask extends AbstractCompanionTask<DropItemsTaskRecord, DropCompanionTask.Dropped> {

    private int dropped;
    /** 丢出去的那一种的名字(带描述),回执里说的就是它。 */
    private String what;
    private String doneMessage = "done";

    public DropCompanionTask(NumenPlayer player, DropItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(
                () -> player.level() instanceof ServerLevel ? null
                        : new Precondition.Failure("not on a server level", FailureType.UNKNOWN),
                () -> Stacks.pick(player.getInventory(), r.item).isPresent() ? null
                        : new Precondition.Failure("no " + r.label + " in inventory to drop",
                                FailureType.NO_MATERIAL));
    }

    @Override
    protected void onStart() {
        tryDrop();
    }

    /**
     * 丢之前交给权限层(出厂规则每次都问):放行就丢、当刻收场;要问就等主人点头,这次调用悬着;
     * 不许就带着理由收场。
     */
    private TaskState tryDrop() {
        Permit permit = gate().permit(com.dwinovo.numen.api.permission.Action.drop(r.item));
        if (permit.state() == PermitState.WAITING) {
            return TaskState.RUNNING;
        }
        if (permit.state() == PermitState.REFUSED) {
            fail("did not drop " + r.label + ": " + permit.refusal(), FailureType.REFUSED);
            return TaskState.FAILED;
        }
        Inventory inv = player.getInventory();
        // 丢的是背包里<b>真实的那几叠</b>:从格子里拆出来的栈带着自己的全部组件
        // (附魔/耐久/改名/容器内容物),不按数量销毁再 new ItemStack 重造——凭空重造只属于创造模式的 creative give。
        // 丢哪一种由 Stacks.pick 定(只说了 id、背包里有几种不同的叠时,是主手那叠、否则第一格那叠),只丢这一种,
        // 别的叠一件不动;回执说丢的是哪一种、剩下的还有哪些。
        // Toss like a real player: native Player.drop(stack, false) throws each stack in the facing
        // direction with vanilla motion + pickup delay and fires the drop event (mods watching item
        // tosses see it) — instead of hand-building an ItemEntity with a made-up velocity.
        Optional<Stacks.Pick> pick = Stacks.pick(inv, r.item);
        dropped = Math.min(r.count, pick.map(Stacks.Pick::count).orElse(0));
        int remaining = dropped;
        for (int slot : pick.map(Stacks.Pick::slots).orElse(List.of())) {
            if (remaining <= 0) break;
            ItemStack out = inv.removeItem(slot, Math.min(inv.getItem(slot).getCount(), remaining));
            remaining -= out.getCount();
            player.drop(out, false);
        }
        inv.setChanged();
        what = pick.map(p -> StackLabel.brief(p.sample())).orElse(r.label);
        doneMessage = "dropped " + dropped + "x " + what
                + (dropped < r.count ? " (only had " + dropped + ")" : "")
                + others(inv, pick);
        succeed();   // work is done — finalize this same tick, before a Stop can mislabel it
        return TaskState.SUCCESS;
    }

    @Override
    protected TaskState onTick() {
        return tryDrop();
    }

    /** No nav / overlay to release. */
    @Override
    protected void cleanup() {}

    /**
     * 同一种物品里没丢的别的叠,一句话:模型指的是哪一叠由它自己定,丢完得知道另外还剩什么。没有别的叠为空串。
     */
    private static String others(Inventory inv, Optional<Stacks.Pick> pick) {
        if (pick.isEmpty()) {
            return "";
        }
        List<String> left = new ArrayList<>();
        for (Stacks.Kind kind : Stacks.kinds(inv.items)) {
            if (kind.sample().is(pick.get().sample().getItem())
                    && !Stacks.sameKind(kind.sample(), pick.get().sample())) {
                left.add(StackLabel.brief(kind.sample()) + " x" + kind.count());
            }
        }
        return left.isEmpty() ? "" : "; other stacks of it stay: " + String.join(", ", left);
    }

    /** 丢了几件、还剩几件:{@code numen.inv.drop} 交回的值。{@code item} 带描述,说的是丢的那一种;{@code remainingInInventory} 是这个物品背包里各叠加起来还剩几件。 */
    public record Dropped(String item, int dropped, int remainingInInventory) {}

    @Override
    protected Dropped value() {
        return new Dropped(what == null ? r.label : what, dropped,
                PlayerInv.carriedCount(player.getInventory(), r.item));
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
