package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code eat_item} on the player body — a thin wrapper over the native held use. Hold the food and
 * run {@link Interaction#useInAir} on a {@code hold()} timing: that fires {@code gameMode.useItem},
 * and the body's own {@code aiStep} (ticked via {@code doTick}) drives the real eat to completion —
 * chewing animation/particles/sound, hunger + saturation + consume-effects on finish, modded foods,
 * and any mod events. No hand-rolled chew loop or fake direct heal: the companion is a ServerPlayer
 * with a live {@code FoodData}, so eating works exactly as it does for a real player (full hunger →
 * it simply won't eat, which we detect and report).
 */
public final class EatCompanionTask extends AbstractCompanionTask<EatItemTaskRecord> {

    private Interaction eat;
    private int beforeCount;
    private float beforeHp;
    private int beforeFood;
    private String doneMessage = "done";

    public EatCompanionTask(NumenPlayer player, EatItemTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(
                // 无饥饿画像(创造)下:吃的动作会执行但食物不扣、饥饿不涨,
                // 事后一切判定都失真——直接如实拒绝,别让模型收到"already full"的假诊断。
                () -> WorkProfile.of(player).hasHunger() ? null
                        : new Precondition.Failure(
                                "creative mode has no hunger — eating is unnecessary; kept the "
                                        + r.label, FailureType.UNKNOWN),
                () -> PlayerInv.count(player.getInventory(), r.item) > 0 ? null
                        : new Precondition.Failure("no " + r.label + " in inventory to eat",
                                FailureType.NO_MATERIAL),
                // Native "is this consumable?" — covers food, potions, milk, and modded consumables alike.
                () -> new ItemStack(r.item).get(DataComponents.FOOD) != null ? null
                        : new Precondition.Failure(r.label + " can't be eaten or drunk",
                                FailureType.UNKNOWN));
    }

    @Override
    protected void onStart() {
        beforeCount = PlayerInv.count(player.getInventory(), r.item);
        beforeHp = player.getHealth();
        beforeFood = player.getFoodData().getFoodLevel();
        // Equip the food, then start a native held use. The use() call decides whether eating begins
        // (e.g. full hunger on non-always-eat food won't start) — we read the outcome on completion.
        player.holdInHand(PlayerInv.findSlot(player.getInventory(), r.item));
        eat = Interaction.useInAir(player, InteractionHand.MAIN_HAND, Interaction.Timing.hold());
    }

    @Override
    protected TaskState onTick() {
        return switch (eat.tick()) {
            case DONE -> finish();
            case FAILED -> {
                fail("couldn't eat " + r.label + ": " + eat.failReason(), FailureType.UNKNOWN);
                yield TaskState.FAILED;
            }
            case RUNNING -> TaskState.RUNNING;
        };
    }

    /** The held use finished. If an item was actually consumed, report the hunger/HP it restored;
     *  otherwise the body declined to eat (typically already full) — say so rather than claim success. */
    private TaskState finish() {
        int now = PlayerInv.count(player.getInventory(), r.item);
        if (now >= beforeCount) {
            fail("didn't eat " + r.label + " — already full (hunger " + beforeFood + "/20). Kept it.",
                    FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        int foodGain = player.getFoodData().getFoodLevel() - beforeFood;
        float healed = player.getHealth() - beforeHp;
        doneMessage = "ate " + r.label + " — hunger " + player.getFoodData().getFoodLevel() + "/20"
                + (foodGain > 0 ? " (+" + foodGain + ")" : "")
                + (healed > 0.0f ? ", HP " + fmt(player.getHealth()) + " (+" + fmt(healed) + ")" : "");
        return TaskState.SUCCESS;
    }

    private static String fmt(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v);
    }

    /** Release the held use; no nav / overlay to clear. */
    @Override
    protected void cleanup() {
        if (eat != null) {
            eat.stop();
        }
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("hp", player.getHealth());
        data.put("hunger", player.getFoodData().getFoodLevel());
        return data;
    }

    @Override
    protected String successMessage() {
        return doneMessage;
    }

    @Override
    protected String timeoutMessage() {
        return "couldn't finish eating " + r.label;
    }

    @Override
    protected String cancelledMessage() {
        return "eating " + r.label + " interrupted — no effect";
    }
}
