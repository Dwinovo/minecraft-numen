package com.dwinovo.numen.core.task.collect;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.mixin.ItemEntityAccessor;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.TargetSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Intent-level item sweeper for {@link CollectItemsTaskRecord}: "pick up the
 * dropped items around here." The entity already auto-absorbs items within ~1
 * block ({@code setCanPickUpLoot}); this goal actively walks it to each
 * scattered drop with the pathfinder so nothing is left behind after a mine or a
 * attack.
 *
 * <h2>State machine (per tick)</h2>
 * <pre>
 *   SCAN     → nearest matching ItemEntity within the radius; none → DONE.
 *   APPROACH → Navigator toward it until it's absorbed or we
 *              reach the spot without picking it up, then re-SCAN. At the spot a
 *              fresh drop still counting down its pickup delay is waited out;
 *              anything else that stays on the ground is skipped.
 * </pre>
 *
 * <p>SCAN ends only once every matching drop in range has been tried, so whatever
 * still lies there at the end is what she couldn't pick up — the reply names it.
 *
 * <p>回执里捡了多少,数的是到手的件数:背包里要捡的那几种比开工时多出来的,不是消失了几堆掉落物
 * ——一堆可能是好几个,消失的也可能是被别人捡走、到时候没了。
 */
public final class CollectItemsCompanionTask extends AbstractCompanionTask<CollectItemsTaskRecord> {

    private enum Phase { SCAN, APPROACH }

    private static final double WALK_SPEED = 1.0;
    /** Close enough that vanilla auto-pickup should have absorbed the item (≈1.2 blocks). */
    private static final double PICKUP_REACH_SQR = 1.5;

    private Phase phase = Phase.SCAN;
    private ItemEntity target;

    /** Item-entity ids we reached but couldn't absorb, so SCAN won't loop on them. */
    private final TargetSet<ItemEntity> skipped = new TargetSet<>(ItemEntity::getId);
    /** 开工时背包里已经有多少要捡的东西;到手的件数从这里往上数。 */
    private int baseline;

    public CollectItemsCompanionTask(NumenPlayer player, CollectItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        this.phase = Phase.SCAN;
        baseline = carried();
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) {
            return TaskState.CANCELLED;
        }
        r.setCollected(Math.max(0, carried() - baseline));
        return switch (phase) {
            case SCAN -> tickScan();
            case APPROACH -> tickApproach();
        };
    }

    private TaskState tickScan() {
        ItemEntity best = nearestItem();
        if (best == null) {
            // Nothing left within radius — done. Success even at 0 (the LLM asked
            // us to sweep; "nothing here" is a valid, useful answer).
            return TaskState.SUCCESS;
        }
        target = best;
        nav = new PlayerNav(player, this::targetCell, WALK_SPEED, this::picked);
        phase = Phase.APPROACH;
        return TaskState.RUNNING;
    }

    private TaskState tickApproach() {
        if (target == null || target.isRemoved()) {
            // Absorbed (by us or otherwise); what she actually got is counted off the inventory
            stopNav();
            phase = Phase.SCAN;
            return TaskState.RUNNING;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* walking to it */ }
            case ARRIVED -> {
                // Reached the spot. If it's now absorbed, the removed-branch above
                // counts it next tick. A drop still in its pickup delay is absorbed by
                // standing here once the delay runs out; otherwise we can't pick it up
                // — skip it.
                if (!target.isRemoved() && !pickupPending(target)) {
                    skipped.skip(target);
                    target = null;
                    stopNav();
                    phase = Phase.SCAN;
                }
            }
            case FAILED -> {                 // can't route to it — abandon
                if (target != null) skipped.skip(target);
                target = null;
                stopNav();
                phase = Phase.SCAN;
            }
        }
        return TaskState.RUNNING;
    }

    private BlockPos targetCell() {
        return (target != null && !target.isRemoved()) ? target.blockPosition() : null;
    }

    /** Still counting down its pickup delay (vanilla gives fresh drops a few ticks) — not
     *  the "never" marker, which no amount of waiting clears. */
    private static boolean pickupPending(ItemEntity item) {
        int delay = ((ItemEntityAccessor) item).numen$getPickupDelay();
        return delay > 0 && delay != ItemEntityAccessor.numen$infinitePickupDelay();
    }

    /** Reached = absorbed, or close enough that auto-pickup should have fired. */
    private boolean picked() {
        return target == null || target.isRemoved()
                || player.distanceToSqr(target) <= PICKUP_REACH_SQR;
    }

    /** 背着的、要捡的那几种一共多少个(没点名就是全部)。 */
    private int carried() {
        return com.dwinovo.numen.core.PlayerInv.carriedCount(player.getInventory(),
                s -> r.filter.isEmpty() || r.filter.contains(s.getItem()));
    }

    private ItemEntity nearestItem() {
        return skipped.pick(matchingItems(), Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    /** Every drop in range that this sweep is after, tried or not. */
    private List<ItemEntity> matchingItems() {
        AABB box = player.getBoundingBox().inflate(r.radius);
        List<ItemEntity> candidates = new ArrayList<>();
        for (Entity e : player.level().getEntities(player, box)) {
            if (!(e instanceof ItemEntity ie) || ie.isRemoved()) continue;
            if (!r.filter.isEmpty() && !r.filter.contains(ie.getItem().getItem())) continue;
            candidates.add(ie);
        }
        return candidates;
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("label", r.label);
        data.put("collected", r.getCollected());
        data.put("radius", r.radius);
        return data;
    }

    @Override
    protected String successMessage() {
        String collected = "collected " + r.getCollected() + " " + r.label;
        List<ItemEntity> left = matchingItems();
        if (left.isEmpty()) {
            return collected;
        }
        int count = left.stream().mapToInt(e -> e.getItem().getCount()).sum();
        BlockPos at = left.stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElseThrow()
                .blockPosition();
        return collected + "; " + count + " more lie where I couldn't pick them up (nearest at "
                + at.getX() + "," + at.getY() + "," + at.getZ() + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after collecting " + r.getCollected() + " " + r.label;
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after collecting " + r.getCollected() + " " + r.label;
    }
}
