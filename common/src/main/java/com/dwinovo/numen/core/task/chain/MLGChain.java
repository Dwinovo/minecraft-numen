package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.task.BodyLog;
import com.dwinovo.numen.task.reflex.Reflex;

import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.task.SurvivalConfig;
import com.dwinovo.numen.task.TaskChain;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Autonomous fall-save (MLG) survival chain. Polls the fall state each tick; while
 * the body is airborne past a lethal-ish fall distance and holds a water bucket (or
 * a soft block), it spikes above everything, aims straight down, and — once the
 * ground is within reach — empties the bucket (or places the block) to break the
 * fall, then drops back once it lands or runs out of ways to save itself.
 *
 * <p>Best-effort and deliberately small in scope: the water-bucket path is the
 * reliable one (a straight-down {@code useItem} lets the vanilla bucket place water
 * on the block below); the soft-block path is a rough fallback that clicks the block
 * onto the ground it is about to hit. Drives the {@link Interaction} primitive
 * directly — there is no nav and no result to build.
 *
 * <p>GATED OFF by default via {@link SurvivalConfig}.
 *
 * <p><b>Emptied-bucket safety:</b> the water source is re-scanned every tick, so
 * once the bucket empties (it becomes a plain bucket) the chain no longer fires a
 * use — it never scoops the just-placed water back up.
 */
public final class MLGChain implements TaskChain, com.dwinovo.numen.task.reflex.Reflex {

    /** Fire the water/block once the ground is this close below (blocks). */
    private static final double PLACE_WITHIN = 3.5;
    /** How far down to probe for ground. */
    private static final double PROBE_DEPTH = 8.0;

    /** BodyLog for completed episodes — dual-rail routed (may be null in unit tests). */
    private final com.dwinovo.numen.task.BodyLog bodyLog;
    /** One diary line per fall episode (reset when the save ends). */
    private boolean notedThisFall;

    public MLGChain() {
        this(null);
    }

    public MLGChain(com.dwinovo.numen.task.BodyLog bodyLog) {
        this.bodyLog = bodyLog;
    }

    @Override
    public float getPriority(NumenPlayer companion) {
        if (!SurvivalConfig.enabled()) return Float.NEGATIVE_INFINITY;
        if (!com.dwinovo.numen.task.reflex.ReflexRegistry.enabled(id())) {
            return SurvivalDecisions.DORMANT;   // reflex switched off by the owner
        }
        boolean canSave = waterBucketSlot(companion) >= 0 || softBlockSlot(companion) >= 0;
        return SurvivalDecisions.mlgPriority(companion.onGround(), companion.fallDistance, canSave);
    }

    @Override
    public void tick(NumenPlayer companion) {
        // Aim straight down every tick — both the bucket and a block placement raycast
        // along the current look.
        companion.setXRot(90.0f);
        double toGround = distanceToGround(companion);
        if (toGround > PLACE_WITHIN) return;   // hold the save until we're close enough

        int bucket = waterBucketSlot(companion);
        if (bucket >= 0) {
            companion.holdInHand(bucket);
            // Straight-down useItem: the vanilla water bucket places water on the block below.
            Interaction.useInAir(companion, InteractionHand.MAIN_HAND, Interaction.Timing.once()).tick();
            noteSave(companion, "a water bucket");
            return;
        }
        int block = softBlockSlot(companion);
        if (block >= 0) {
            companion.holdInHand(block);
            BlockHitResult hit = groundHit(companion);
            if (hit != null) {
                // Rough best-effort: click the soft block onto the ground we're about to hit.
                Interaction.useBlock(companion, hit, InteractionHand.MAIN_HAND).tick();
                noteSave(companion, "a soft block");
            }
        }
    }

    /** One diary line per fall episode, stamped with the height it survived. */
    private void noteSave(NumenPlayer companion, String means) {
        if (bodyLog == null || notedThisFall) return;
        notedThisFall = true;
        bodyLog.report("broke a " + (int) companion.fallDistance + "-block fall with " + means);
    }

    @Override
    public void onInterrupt(NumenPlayer companion) {
        if (companion.isUsingItem()) {
            companion.releaseUsingItem();
        }
        companion.setXRot(0.0f);   // stop staring straight down; the resumed task re-aims as needed
        notedThisFall = false;     // the fall episode is over — the next fall diaries anew
    }

    @Override
    public String name() {
        return "mlg";
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "高处坠落时会用水桶或软方块自救";
    }

    /** Distance from the feet to the first solid block below, or a large number if none within probe. */
    private static double distanceToGround(NumenPlayer companion) {
        BlockHitResult hit = groundHit(companion);
        if (hit == null) return Double.MAX_VALUE;
        return companion.position().y - hit.getLocation().y;
    }

    private static BlockHitResult groundHit(NumenPlayer companion) {
        Vec3 from = companion.position();
        Vec3 to = from.add(0.0, -PROBE_DEPTH, 0.0);
        BlockHitResult hit = companion.level().clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, companion));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static int waterBucketSlot(NumenPlayer companion) {
        Inventory inv = companion.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(Items.WATER_BUCKET)) return i;
        }
        return -1;
    }

    /** Slot of a placeable fall-dampening block (hay / slime), or -1. */
    private static int softBlockSlot(NumenPlayer companion) {
        Inventory inv = companion.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.HAY_BLOCK) || s.is(Items.SLIME_BLOCK)) return i;
        }
        return -1;
    }
}
