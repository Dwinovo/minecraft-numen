package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.core.act.Ballistics;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/** Executes only the ranged-use and kinetic-spear actions selected by CombatTacticPolicy. */
public final class CombatWeaponController {
    public enum Result {
        HANDLED,
        UNAVAILABLE
    }

    private static final int BOW_RELEASE_TICKS = 15;
    private static final int BOW_MAX_DRAW_TICKS = 40;
    private static final int CROSSBOW_LOAD_TIMEOUT = 80;
    private static final int MAX_NO_WINDOW_TICKS = 40;
    private static final int RANGED_RETRY_COOLDOWN = 60;
    private static final Map<NumenPlayer, State> STATES = new WeakHashMap<>();

    private CombatWeaponController() {
    }

    public static boolean rangedReady(
        NumenPlayer player,
        LivingEntity target,
        CombatWeaponSelector.Loadout loadout
    ) {
        if (!loadout.rangedReady()) {
            return false;
        }
        State state = STATES.get(player);
        return state == null
            || state.targetId != target.getId()
            || player.level().getGameTime() >= state.rangedBlockedUntil;
    }

    public static boolean rangedShotInProgress(NumenPlayer player, LivingEntity target) {
        State state = STATES.get(player);
        return state != null
            && state.targetId == target.getId()
            && (isUsingRangedWeapon(player) || state.held > 0);
    }

    public static Result tickRanged(
        NumenPlayer player,
        LivingEntity target,
        CombatWeaponSelector.Candidate weapon
    ) {
        if (weapon == null || !player.hasLineOfSight(target)) {
            reset(player);
            return Result.UNAVAILABLE;
        }
        State state = stateFor(player, target);
        if (player.level().getGameTime() < state.rangedBlockedUntil) {
            return Result.UNAVAILABLE;
        }
        if (player.isUsingItem() && !isUsingRangedWeapon(player)) {
            if (player.getUseItem().is(ItemTags.SPEARS)) {
                stopCombatUse(player);
            } else {
                return Result.HANDLED;
            }
        }
        if (!CombatWeaponSelector.hold(player, weapon)) {
            stopCombatUse(player);
            state.resetShot();
            return Result.HANDLED;
        }

        ItemStack stack = player.getMainHandItem();
        if (!matches(stack, weapon.kind())) {
            blockRanged(player, state);
            return Result.UNAVAILABLE;
        }

        InputDriver.halt(player);
        double velocity = weapon.kind() == CombatWeaponSelector.Kind.CROSSBOW
            ? 3.15
            : 3.0 * bowPowerForTicks(Math.max(BOW_RELEASE_TICKS, state.held + 1));
        Ballistics.Aim aim = Ballistics.findArrowShot(
            player.level(),
            player,
            target,
            velocity,
            0.05,
            0.99,
            0.5,
            32.0,
            weapon.kind() == CombatWeaponSelector.Kind.BOW
        );
        if (aim == null) {
            if (++state.noWindowTicks >= MAX_NO_WINDOW_TICKS) {
                blockRanged(player, state);
                return Result.UNAVAILABLE;
            }
            return Result.HANDLED;
        }
        state.noWindowTicks = 0;
        InputDriver.lookAt(player, aim.lookPoint());

        if (state.settleTicks > 0) {
            state.settleTicks--;
            return Result.HANDLED;
        }
        if (weapon.kind() == CombatWeaponSelector.Kind.CROSSBOW) {
            tickCrossbow(player, stack, aim, state);
        } else {
            tickBow(player, aim, state);
        }
        return Result.HANDLED;
    }

    public static Result tickSpear(
        NumenPlayer player,
        LivingEntity target,
        CombatWeaponSelector.Candidate spear
    ) {
        if (spear == null || !player.hasLineOfSight(target)) {
            reset(player);
            return Result.UNAVAILABLE;
        }
        State state = stateFor(player, target);
        if (player.isUsingItem() && !player.getUseItem().is(ItemTags.SPEARS)) {
            if (isUsingRangedWeapon(player)) {
                stopCombatUse(player);
            } else {
                return Result.HANDLED;
            }
        }
        if (!CombatWeaponSelector.hold(player, spear)) {
            stopCombatUse(player);
            state.resetShot();
            return Result.HANDLED;
        }
        ItemStack stack = player.getMainHandItem();
        if (!stack.is(ItemTags.SPEARS)) {
            return Result.UNAVAILABLE;
        }

        InputDriver.lookAt(player, target.getEyePosition());
        if (!player.isUsingItem()) {
            player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND);
        }
        return Result.HANDLED;
    }

    public static void reset(NumenPlayer player) {
        State removed = STATES.remove(player);
        if (removed != null) {
            stopCombatUse(player);
        }
    }

    /** Stops the current weapon action without discarding a per-target ranged retry cooldown. */
    public static void pause(NumenPlayer player) {
        State state = STATES.get(player);
        if (state == null) {
            return;
        }
        stopUsingCombatItem(player);
        state.resetShot();
    }

    private static void tickBow(NumenPlayer player, Ballistics.Aim aim, State state) {
        if (!player.isUsingItem()) {
            player.gameMode.useItem(
                player,
                player.level(),
                player.getMainHandItem(),
                InteractionHand.MAIN_HAND
            );
            state.held = 0;
            return;
        }
        state.held++;
        double angle = Ballistics.angleDegrees(player.getViewVector(1.0F), aim.direction());
        if ((angle <= 1.5 && state.held >= BOW_RELEASE_TICKS)
            || state.held >= BOW_MAX_DRAW_TICKS) {
            player.releaseUsingItem();
            state.resetShot();
            state.settleTicks = 4;
        }
    }

    private static void tickCrossbow(
        NumenPlayer player,
        ItemStack stack,
        Ballistics.Aim aim,
        State state
    ) {
        if (CrossbowItem.isCharged(stack)) {
            if (Ballistics.angleDegrees(player.getViewVector(1.0F), aim.direction()) <= 1.5) {
                player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND);
                state.resetShot();
                state.settleTicks = 4;
            }
            return;
        }
        if (!player.isUsingItem()) {
            player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND);
            state.held = 0;
            return;
        }
        state.held++;
        if (state.held >= CrossbowItem.getChargeDuration(stack, player)) {
            player.releaseUsingItem();
            state.held = 0;
        } else if (state.held >= CROSSBOW_LOAD_TIMEOUT) {
            blockRanged(player, state);
        }
    }

    private static State stateFor(NumenPlayer player, LivingEntity target) {
        State state = STATES.get(player);
        if (state == null || state.targetId != target.getId()) {
            reset(player);
            state = new State(target.getId());
            STATES.put(player, state);
        }
        return state;
    }

    private static void blockRanged(NumenPlayer player, State state) {
        stopCombatUse(player);
        state.resetShot();
        state.rangedBlockedUntil = player.level().getGameTime() + RANGED_RETRY_COOLDOWN;
    }

    private static void stopCombatUse(NumenPlayer player) {
        stopUsingCombatItem(player);
        InputDriver.halt(player);
    }

    private static void stopUsingCombatItem(NumenPlayer player) {
        if (player.isUsingItem()
            && (player.getUseItem().getItem() instanceof BowItem
                || player.getUseItem().getItem() instanceof CrossbowItem
                || player.getUseItem().is(ItemTags.SPEARS))) {
            player.stopUsingItem();
        }
    }

    private static boolean isUsingRangedWeapon(NumenPlayer player) {
        return player.isUsingItem()
            && (player.getUseItem().getItem() instanceof BowItem
                || player.getUseItem().getItem() instanceof CrossbowItem);
    }

    private static boolean matches(ItemStack stack, CombatWeaponSelector.Kind kind) {
        return switch (kind) {
            case BOW -> stack.getItem() instanceof BowItem;
            case CROSSBOW -> stack.getItem() instanceof CrossbowItem;
            case SPEAR -> stack.is(ItemTags.SPEARS);
            case MELEE -> false;
        };
    }

    private static double bowPowerForTicks(int ticks) {
        double draw = ticks / 20.0;
        return Math.min(1.0, Math.max(0.0, (draw * draw + draw * 2.0) / 3.0));
    }

    private static final class State {
        private final int targetId;
        private int held;
        private int settleTicks;
        private int noWindowTicks;
        private long rangedBlockedUntil;

        private State(int targetId) {
            this.targetId = targetId;
        }

        private void resetShot() {
            this.held = 0;
            this.settleTicks = 0;
            this.noWindowTicks = 0;
        }
    }
}
