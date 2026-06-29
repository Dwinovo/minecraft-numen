package com.dwinovo.numen.pathing.exec;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The most-native interaction primitive for a fake-player body — modelled on
 * Carpet's {@code EntityPlayerActionPack}: aim the eyes at a target, then "press"
 * one mouse button (left = ATTACK, right = USE) with a {@link Timing}. Every
 * higher-level action is a thin layer on top: {@code break_block} = ATTACK a
 * block (hold), {@code place_block} = USE a block (once), {@code hunt} = ATTACK an
 * entity, eat/bow = hold USE in the air.
 *
 * <h2>Native dispatch (same calls Carpet's action pack makes)</h2>
 * <ul>
 *   <li>ATTACK + block  → {@link BlockDigger} (creative insta / survival timed) → {@code handleBlockBreakAction} START/STOP (server destroys)</li>
 *   <li>ATTACK + entity → {@code player.attack} (cooldown-scaled damage / sweep / knockback)</li>
 *   <li>USE + block     → {@code gameMode.useItemOn} (vanilla place / activate), both hands tried</li>
 *   <li>USE + entity    → {@code entity.interact} then {@code player.interactOn} (trade / breed / mount), both hands</li>
 *   <li>USE + air       → {@code gameMode.useItem} (+ a hold for food / bow)</li>
 * </ul>
 *
 * <h2>Timing (Carpet's {@code Action} model, first-class)</h2>
 * {@link Timing#once()} taps once; {@link Timing#repeat} taps N times spaced by an
 * interval (auto-click a button, grind a mob); {@link Timing#hold()} holds the
 * button until the action self-completes (a block breaks, food finishes);
 * {@link Timing#hold(int)} holds up to N ticks then releases (draw + loose a bow).
 *
 * <p>Stateful + ticked (like {@link BlockDigger} / {@code PlayerNav}). The caller
 * walks the body within reach first; this only aims and presses.
 */
public final class Interaction {

    public enum Status { RUNNING, DONE, FAILED }
    public enum Button { ATTACK, USE }

    /** Vanilla block-interaction reach (survival); creative is 5. */
    private static final double REACH = 4.5;
    /** The two hands USE tries, main first (Carpet tries both). */
    private static final InteractionHand[] HANDS = {InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND};

    /**
     * When and how often the button fires — a port of Carpet's {@code Action}
     * (once / continuous / interval). {@code hold} actions press-and-hold until
     * the action finishes on its own (breaking, eating) or {@code maxHold} elapses
     * (bow); discrete actions fire {@code limit} times spaced by {@code interval}.
     */
    public static final class Timing {
        final boolean hold;
        final int limit;     // discrete fires (>=1); ignored for hold
        final int interval;  // ticks between discrete fires (>=1)
        final int maxHold;   // hold: release after this many ticks; 0 = until self-complete

        private Timing(boolean hold, int limit, int interval, int maxHold) {
            this.hold = hold;
            this.limit = limit;
            this.interval = interval;
            this.maxHold = maxHold;
        }

        /** One single press. */
        public static Timing once() {
            return new Timing(false, 1, 1, 0);
        }

        /** {@code times} presses, each spaced {@code interval} ticks apart. */
        public static Timing repeat(int times, int interval) {
            return new Timing(false, Math.max(1, times), Math.max(1, interval), 0);
        }

        /** Hold until the action finishes on its own (block broken / food eaten). */
        public static Timing hold() {
            return new Timing(true, -1, 1, 0);
        }

        /** Hold up to {@code maxTicks}, then release (e.g. draw a bow and loose). */
        public static Timing hold(int maxTicks) {
            return new Timing(true, -1, 1, Math.max(1, maxTicks));
        }
    }

    private final NumenPlayer player;
    private final Button button;
    private final BlockPos block;     // non-null → block target
    private final Entity entity;      // non-null → entity target
    private final InteractionHand hand;
    private final Timing timing;

    private final BlockDigger digger; // only for ATTACK + block
    private BlockHitResult presetHit; // USE+block: an exact hit the caller already resolved (placement)
    private int fires;
    private int cooldown;             // ticks until the next discrete press
    private boolean started;          // USE+air: the hold has begun
    private int held;                 // USE+air: ticks held so far
    private boolean hardFail;         // a fire hit an unrecoverable error
    private String failReason = "interaction failed";

    private Interaction(NumenPlayer player, Button button, BlockPos block, Entity entity,
                        InteractionHand hand, Timing timing) {
        this.player = player;
        this.button = button;
        this.block = block == null ? null : block.immutable();
        this.entity = entity;
        this.hand = hand;
        this.timing = timing;
        this.digger = (button == Button.ATTACK && block != null) ? new BlockDigger(player) : null;
    }

    // ---- factories (default timings; overloads take an explicit Timing) ----

    /** Left-click a block: break it (held until gone; creative insta / survival timed). */
    public static Interaction attackBlock(NumenPlayer p, BlockPos pos) {
        return new Interaction(p, Button.ATTACK, pos, null, InteractionHand.MAIN_HAND, Timing.hold());
    }

    /** Left-click an entity once (cooldown-gated native attack). */
    public static Interaction attackEntity(NumenPlayer p, Entity target) {
        return attackEntity(p, target, Timing.once());
    }

    public static Interaction attackEntity(NumenPlayer p, Entity target, Timing timing) {
        return new Interaction(p, Button.ATTACK, null, target, InteractionHand.MAIN_HAND, timing);
    }

    /** Right-click a block: place / activate with the held item (raycasts to {@code pos}). */
    public static Interaction useBlock(NumenPlayer p, BlockPos pos, InteractionHand hand) {
        return new Interaction(p, Button.USE, pos, null, hand, Timing.once());
    }

    /** Right-click a pre-resolved block hit — placement / precise activation supplies
     *  the exact support face, so this skips the raycast and presses against {@code hit}. */
    public static Interaction useBlock(NumenPlayer p, BlockHitResult hit, InteractionHand hand) {
        Interaction i = new Interaction(p, Button.USE, hit.getBlockPos(), null, hand, Timing.once());
        i.presetHit = hit;
        return i;
    }

    /** Right-click an entity: trade / breed / mount / name. */
    public static Interaction useEntity(NumenPlayer p, Entity target, InteractionHand hand) {
        return new Interaction(p, Button.USE, null, target, hand, Timing.once());
    }

    /** Right-click in the air with the held item, on the given {@link Timing}
     *  ({@code hold()} eats food / {@code hold(n)} draws and looses a bow). */
    public static Interaction useInAir(NumenPlayer p, InteractionHand hand, Timing timing) {
        return new Interaction(p, Button.USE, null, null, hand, timing);
    }

    /** vanilla {@code Minecraft.rightClickDelay} — held right-click re-fires this often. */
    private static final int RIGHT_CLICK_DELAY = 4;
    /** A "hold forever" fire count; the owning task stops us after hold_ticks / on completion. */
    private static final int CONTINUOUS = 1_000_000;

    /**
     * Carpet's {@code Tracer} / vanilla crosshair pick: one ray from the eyes along the CURRENT
     * look, resolving the CLOSER of a block or an entity (else MISS). A wall occludes a mob behind
     * it (entities are searched only as near as the block hit). {@code reach} 4.5 = survival.
     */
    public static HitResult nativeRaytrace(NumenPlayer player, double reach) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 reachVec = player.getViewVector(1.0f).scale(reach);
        Vec3 end = eye.add(reachVec);
        BlockHitResult block = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double maxSq = block.getType() == HitResult.Type.MISS
                ? reach * reach : block.getLocation().distanceToSqr(eye);
        AABB box = player.getBoundingBox().expandTowards(reachVec).inflate(1.0);
        EntityHitResult ent = ProjectileUtil.getEntityHitResult(
                player, eye, end, box, e -> !e.isSpectator() && e.isPickable(), maxSq);
        return ent != null ? ent : block;   // entity (closer than the block) wins, else the block/miss
    }

    /**
     * Build the native action for a resolved crosshair {@code hit} + {@code button}, mapping
     * {@code holdTicks} to the cell's natural cadence — Carpet's 6-cell dispatch:
     * <ul>
     *   <li>ATTACK·BLOCK → break (BlockDigger holds till the block is gone);</li>
     *   <li>ATTACK·ENTITY → hit (tap = one cooldown-gated hit; hold = keep hitting);</li>
     *   <li>USE·BLOCK → activate (tap once; hold re-clicks every rightClickDelay — modded crank);</li>
     *   <li>USE·ENTITY → interact (tap once; hold re-clicks);</li>
     *   <li>USE·AIR → useItem (tap = throw; hold = charge/eat up to ticks, or self-complete);</li>
     *   <li>ATTACK·AIR → {@code null} (left-click air does nothing).</li>
     * </ul>
     * {@code holdTicks}: 0 = tap, &gt;0 / -1 = hold. The block/entity hit is used verbatim (the
     * native raytrace already resolved the exact face/point — no re-raycast). The caller drives
     * the returned object to completion and enforces the hold duration.
     */
    public static Interaction forHit(NumenPlayer p, HitResult hit, Button button, int holdTicks) {
        boolean hold = holdTicks != 0;
        switch (hit.getType()) {
            case BLOCK -> {
                BlockHitResult bh = (BlockHitResult) hit;
                if (button == Button.ATTACK) {
                    return attackBlock(p, bh.getBlockPos());
                }
                Interaction i = new Interaction(p, Button.USE, bh.getBlockPos(), null,
                        InteractionHand.MAIN_HAND,
                        hold ? Timing.repeat(CONTINUOUS, RIGHT_CLICK_DELAY) : Timing.once());
                i.presetHit = bh;   // use the robust native hit, no re-raycast
                return i;
            }
            case ENTITY -> {
                Entity e = ((EntityHitResult) hit).getEntity();
                if (button == Button.ATTACK) {
                    return attackEntity(p, e, hold ? Timing.repeat(CONTINUOUS, 1) : Timing.once());
                }
                return new Interaction(p, Button.USE, null, e, InteractionHand.MAIN_HAND,
                        hold ? Timing.repeat(CONTINUOUS, RIGHT_CLICK_DELAY) : Timing.once());
            }
            default -> {   // MISS = air
                if (button == Button.ATTACK) {
                    return null;
                }
                Timing t = hold ? (holdTicks > 0 ? Timing.hold(holdTicks) : Timing.hold()) : Timing.once();
                return useInAir(p, InteractionHand.MAIN_HAND, t);
            }
        }
    }

    public String failReason() {
        return failReason;
    }

    public Status tick() {
        if (button == Button.ATTACK && block != null) {
            return breakBlock();                       // inherently continuous
        }
        if (button == Button.USE && block == null && entity == null) {
            return useAir();
        }
        return discrete();                             // attack entity / use block / use entity
    }

    // ---- ATTACK + block: continuous break ----

    private Status breakBlock() {
        if (player.level().getBlockState(block).isAir()) return Status.DONE;
        return digger.dig(block) ? Status.DONE : Status.RUNNING;
    }

    // ---- USE + air: tap or hold (food / bow) ----

    private Status useAir() {
        InputDriver.halt(player);
        if (!started) {
            started = true;
            player.gameMode.useItem(player, player.level(), player.getItemInHand(hand), hand);
            if (!timing.hold) return Status.DONE;      // single tap (throw / instant use)
        }
        if (!player.isUsingItem()) return Status.DONE; // e.g. food finished eating
        if (timing.maxHold > 0 && ++held >= timing.maxHold) {
            player.releaseUsingItem();                 // e.g. loose the bow
            return Status.DONE;
        }
        return Status.RUNNING;
    }

    // ---- discrete: attack entity / use block / use entity (once or repeat) ----

    private Status discrete() {
        if (cooldown > 0) {
            cooldown--;
            return Status.RUNNING;
        }
        boolean fired = switch (button) {
            case ATTACK -> fireAttackEntity();
            case USE -> entity != null ? fireUseEntity() : fireUseBlock();
        };
        if (hardFail) return Status.FAILED;
        if (!fired) return Status.RUNNING;             // soft wait (attack cooldown not ready)
        if (++fires >= timing.limit) return Status.DONE;
        cooldown = timing.interval;
        return Status.RUNNING;
    }

    private boolean fireAttackEntity() {
        if (entity == null || !entity.isAlive()) return false;
        InputDriver.halt(player);
        InputDriver.lookAt(player, entity.getEyePosition());
        if (player.getAttackStrengthScale(0.0f) < 0.95f) {
            return false;                              // wait out the attack cooldown
        }
        player.attack(entity);                         // native damage / cooldown / sweep / knockback
        player.resetAttackStrengthTicker();
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private boolean fireUseBlock() {
        InputDriver.halt(player);
        BlockHitResult hit;
        if (presetHit != null) {
            hit = presetHit;                                  // caller already resolved the support face
            InputDriver.lookAt(player, hit.getLocation());
        } else {
            InputDriver.lookAt(player, Vec3.atCenterOf(block));
            hit = raycastBlock();
            if (hit == null) {
                failReason = "can't see the block to use (out of reach or line of sight blocked)";
                hardFail = true;
                return false;
            }
        }
        for (InteractionHand h : HANDS) {
            InteractionResult res = player.gameMode.useItemOn(
                    player, player.level(), player.getItemInHand(h), h, hit);
            if (res.consumesAction()) {
                player.swing(h);
                return true;
            }
        }
        // Nothing consumed (e.g. empty hand on a non-interactive block) — still a press.
        return true;
    }

    private boolean fireUseEntity() {
        if (entity == null || !entity.isAlive()) {
            failReason = "the entity is gone";
            hardFail = true;
            return false;
        }
        InputDriver.halt(player);
        InputDriver.lookAt(player, entity.getEyePosition());
        for (InteractionHand h : HANDS) {
            if (entity.interact(player, h).consumesAction()) {       // animals / villagers
                return true;
            }
            if (player.interactOn(entity, h).consumesAction()) {     // item frames / leads
                return true;
            }
        }
        return true;   // a press with no effect is still a press
    }

    /** Raycast from the eyes along the current look; the hit must be the target block. */
    private BlockHitResult raycastBlock() {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        Vec3 end = eye.add(look.x * REACH, look.y * REACH, look.z * REACH);
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block)) {
            return hit;
        }
        return null;
    }

    /** Abandon any in-progress interaction (clears a dig overlay / releases a held use). */
    public void stop() {
        if (digger != null) digger.cancel();
        if (player.isUsingItem()) player.releaseUsingItem();
        InputDriver.halt(player);
    }
}
