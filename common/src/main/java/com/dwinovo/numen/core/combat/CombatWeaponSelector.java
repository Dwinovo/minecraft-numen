package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.entity.NumenPlayer;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** Selects one usable weapon for each distance band without fixing all targets to one item. */
public final class CombatWeaponSelector {
    public enum Kind {
        BOW,
        CROSSBOW,
        SPEAR,
        MELEE
    }

    public record Candidate(
        int slot,
        Kind kind,
        double attackDamage,
        boolean usable,
        boolean charged
    ) {
    }

    public record Loadout(Candidate ranged, Candidate spear, Candidate melee) {
        public boolean rangedReady() {
            return ranged != null;
        }

        public boolean spearReady() {
            return spear != null;
        }

        public Candidate meleeOrSpear() {
            return melee != null ? melee : spear;
        }
    }

    private CombatWeaponSelector() {
    }

    public static Loadout choose(List<Candidate> candidates) {
        Candidate ranged = candidates.stream()
            .filter(Candidate::usable)
            .filter(candidate -> candidate.kind() == Kind.BOW || candidate.kind() == Kind.CROSSBOW)
            .min(Comparator.comparingInt(CombatWeaponSelector::rangedRank))
            .orElse(null);
        Candidate spear = strongest(candidates, Kind.SPEAR);
        Candidate melee = strongest(candidates, Kind.MELEE);
        return new Loadout(ranged, spear, melee);
    }

    public static Loadout inspect(NumenPlayer player) {
        Inventory inventory = player.getInventory();
        List<Candidate> candidates = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Kind kind = kindOf(stack);
            if (kind == null) {
                continue;
            }
            boolean charged = kind == Kind.CROSSBOW && CrossbowItem.isCharged(stack);
            boolean usable = switch (kind) {
                case BOW, CROSSBOW -> charged || !player.getProjectile(stack).isEmpty();
                case SPEAR, MELEE -> true;
            };
            candidates.add(new Candidate(slot, kind, attackDamage(stack), usable, charged));
        }
        return choose(candidates);
    }

    public static boolean hold(NumenPlayer player, Candidate candidate) {
        if (candidate == null) {
            return false;
        }
        boolean alreadyHeld = player.getInventory().getSelectedSlot() == candidate.slot();
        player.holdInHand(candidate.slot());
        return alreadyHeld;
    }

    private static int rangedRank(Candidate candidate) {
        if (candidate.kind() == Kind.CROSSBOW && candidate.charged()) {
            return 0;
        }
        return candidate.kind() == Kind.BOW ? 1 : 2;
    }

    private static Candidate strongest(List<Candidate> candidates, Kind kind) {
        return candidates.stream()
            .filter(Candidate::usable)
            .filter(candidate -> candidate.kind() == kind)
            .max(Comparator.comparingDouble(Candidate::attackDamage))
            .orElse(null);
    }

    private static Kind kindOf(ItemStack stack) {
        if (stack.is(ItemTags.SPEARS) && stack.get(DataComponents.KINETIC_WEAPON) != null) {
            return Kind.SPEAR;
        }
        if (stack.getItem() instanceof BowItem) {
            return Kind.BOW;
        }
        if (stack.getItem() instanceof CrossbowItem) {
            return Kind.CROSSBOW;
        }
        return attackDamage(stack) > 0.0 ? Kind.MELEE : null;
    }

    private static double attackDamage(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.getOrDefault(
            DataComponents.ATTRIBUTE_MODIFIERS,
            ItemAttributeModifiers.EMPTY
        );
        double damage = 0.0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.slot().test(EquipmentSlot.MAINHAND)
                && entry.attribute().is(Attributes.ATTACK_DAMAGE)
                && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                damage += entry.modifier().amount();
            }
        }
        return damage;
    }
}
