package com.dwinovo.numen.core.task.collect;

import com.dwinovo.numen.task.Preparation;
import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.mixin.ItemEntityAccessor;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.core.nav.Survey;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.core.scan.NearbyEntities;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.DropTracker;
import com.dwinovo.numen.core.task.base.TargetSet;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.Listing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;

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
 *   SCAN     → nearest matching ItemEntity in the work area; none → DONE.
 *   APPROACH → walk to within a block of it (following it if it slides) until it's absorbed or we
 *              reach the spot without picking it up, then re-SCAN. At the spot a
 *              fresh drop still counting down its pickup delay is waited out;
 *              anything else that stays on the ground is skipped.
 * </pre>
 *
 * <p>SCAN ends only once every matching drop in range has been tried, so whatever
 * still lies there at the end is what she couldn't pick up — the reply names it.
 *
 * <p>受理之前({@link #preparation}):区里一件要捡的都没有、或一件都走不到,当场回那句话,不受理。
 *
 * <p>范围是她的工作区({@link CollectItemsTaskRecord#work}),以受理时她脚下那一格为中心,不跟着她走:捡完一件、站到那边,
 * 下一件仍只在这块地方里找,她不会一件接一件越捡越远;走动也关在区里({@link com.dwinovo.numen.core.nav.WorkArea#confine})。
 *
 * <p>回执里捡了多少,数的是到手的件数:背包里要捡的那几种比开工时多出来的,不是消失了几堆掉落物
 * ——一堆可能是好几个,消失的也可能是被别人捡走、到时候没了。
 */
public final class CollectItemsCompanionTask extends AbstractCompanionTask<CollectItemsTaskRecord> {

    private enum Phase { SCAN, APPROACH }

    /** Close enough that vanilla auto-pickup should have absorbed the item (≈1.2 blocks). */
    private static final double PICKUP_REACH_SQR = 1.5;

    private Phase phase = Phase.SCAN;
    private ItemEntity target;
    /** 在走的这一趟朝着的那一格;掉落物滑走了就换目标。 */
    private BlockPos heading;

    /** Item-entity ids we reached but couldn't absorb, so SCAN won't loop on them. */
    private final TargetSet<ItemEntity> skipped = new TargetSet<>(ItemEntity::getId);
    /** 开工时背包里已经有多少要捡的东西;到手的件数从这里往上数。 */
    private int baseline;
    /** 受理之前的准备规划到的那条路:第一趟照它走;没有、或已经用过为 null。 */
    private Route seed;

    public CollectItemsCompanionTask(NumenPlayer player, CollectItemsTaskRecord record) {
        super(player, record);
    }

    /**
     * 受理之前:区里有没有要捡的掉落物,有的话够不够得着——对区里每一件的"站到捡得起它的地方"一次只搜不走的搜索。一件都没有、
     * 一件都走不到,当场回那句话,没有任务编号;走得到的那条路是第一趟的开头({@link Trip#prepared})。
     */
    @Override
    protected Preparation preparation() {
        List<ItemEntity> lying = matchingItems();
        if (lying.isEmpty()) {
            return Preparation.refused("nothing to pick up: no " + (r.filter.isEmpty() ? "dropped items" : r.label)
                    + " lie " + r.where + ", so I did not start");
        }
        int items = lying.stream().mapToInt(e -> e.getItem().getCount()).sum();
        BlockPos nearest = lying.stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElseThrow()
                .blockPosition();
        String there = lying.size() + " drop(s), " + items + " item(s) in all, lie " + r.where + ", the nearest at "
                + Listing.coords(nearest);
        Survey survey = Survey.of(player, List.of(new Survey.Leg(
                Goals.anyOf(lying.stream().map(e -> goal(e.blockPosition())).toList()), spec())));
        return new Preparation() {
            @Override
            public Preparation.Readiness poll() {
                List<Survey.Found> found = survey.poll();
                if (found == null) {
                    return null;
                }
                Survey.Found leg = found.get(0);
                if (leg.reached()) {
                    seed = leg.route();
                    return Preparation.Readiness.ready(there + "; the first I head for is " + NavText.ahead(seed)
                            + ".");
                }
                return Preparation.Readiness.refused(there + "; I can't get to any of them: "
                        + NavText.failure(leg.outcome(), player, Feet.cell(player), nearest, spec())
                        + "; so I did not start");
            }

            @Override
            public void cancel() {
                survey.cancel();
            }
        };
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
        heading = best.blockPosition();
        // 受理之前规划好的那条只用在第一趟:它通向的也许是别的一件,走到了照样重新挑
        nav = Trip.prepared(player, goal(heading), spec(), seed, heading);
        seed = null;
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
        BlockPos at = target.blockPosition();
        if (!at.equals(heading)) {
            // 掉落物滑走了、被推开了:目标跟着它挪
            heading = at;
            nav.retarget(goal(at), at);
        }
        // 已经挨着它了:原版拾取不等走到那一格,挨着还没进包的就是还在拾取冷却里,或者捡不起来
        Trip.Status status = player.distanceToSqr(target) <= PICKUP_REACH_SQR ? Trip.Status.ARRIVED : nav.tick();
        switch (status) {
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

    /** 走动的规格:不改地形,关在工作区里。 */
    private RouteSpec spec() {
        return r.work.confine(RouteSpec.defaults());
    }

    /** 走到捡得起那一格上的掉落物的地方({@link DropTracker#pickUp})。 */
    private static Goal goal(BlockPos item) {
        return DropTracker.pickUp(item);
    }

    /** Still counting down its pickup delay (vanilla gives fresh drops a few ticks) — not
     *  the "never" marker, which no amount of waiting clears. */
    private static boolean pickupPending(ItemEntity item) {
        int delay = ((ItemEntityAccessor) item).numen$getPickupDelay();
        return delay > 0 && delay != ItemEntityAccessor.numen$infinitePickupDelay();
    }

    /** 背着的、要捡的那几种一共多少个(没点名就是全部)。 */
    private int carried() {
        return com.dwinovo.numen.core.PlayerInv.carriedCount(player.getInventory(),
                s -> r.filter.isEmpty() || r.filter.contains(s.getItem()));
    }

    private ItemEntity nearestItem() {
        return skipped.pick(matchingItems(), Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    /** Every drop in the work area that this sweep is after, tried or not. */
    private List<ItemEntity> matchingItems() {
        return NearbyEntities.in(player.level(), r.area, ItemEntity.class,
                ie -> !ie.isRemoved() && (r.filter.isEmpty() || r.filter.contains(ie.getItem().getItem())));
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("label", r.label);
        data.put("collected", r.getCollected());
        data.put("area", r.where);
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
