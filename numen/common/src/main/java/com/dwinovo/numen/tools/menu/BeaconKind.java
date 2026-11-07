package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.inventory.BeaconMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 信标:金字塔层数、按层数能选的主副效果与当前选中的;选就是 {@code SetBeacon}(主效果,副效果可省),效果用名字({@code "haste"})。
 * 付款物品、层数原版在 {@code updateEffects} 里管:没有付款物品什么也不做,付款被收走(信标界面付款格只放一件)就是成了。
 * 动的是这座信标,交权限层裁决"用这一格"。
 */
final class BeaconKind implements MenuKind.Chooses<BeaconMenu> {

    @Override
    public Class<BeaconMenu> menu() {
        return BeaconMenu.class;
    }

    @Override
    public String name() {
        return "a beacon";
    }

    @Override
    public Screen read(BeaconMenu menu, NumenPlayer her) {
        int levels = menu.getLevels();
        return new Screen.Beacon(levels, menu.hasPayment(), BeaconEffects.names(BeaconEffects.primaries(levels)),
                BeaconEffects.names(BeaconEffects.secondaries(levels)), Optional.ofNullable(menu.getPrimaryEffect())
                        .map(BeaconEffects::name),
                Optional.ofNullable(menu.getSecondaryEffect()).map(BeaconEffects::name));
    }

    @Override
    public boolean changesBlock() {
        return true;
    }

    @Override
    public Action gate(BeaconMenu menu, NumenPlayer her) {
        return MenuKinds.useOpenedBlock(her);
    }

    @Override
    public String choose(BeaconMenu menu, NumenPlayer her, Pick first, Pick second) {
        int levels = menu.getLevels();
        List<Holder<MobEffect>> primaries = BeaconEffects.primaries(levels);
        if (primaries.isEmpty()) {
            throw new ApiError(ErrorKind.FAILED, "this beacon has no pyramid under it (0 levels), so it gives no effect",
                    Call.of("numen.gui.view"));
        }
        Holder<MobEffect> primary = effect(first, primaries, "primary", levels);
        Holder<MobEffect> secondary = null;
        if (second != null) {
            List<Holder<MobEffect>> allowed = new ArrayList<>(BeaconEffects.secondaries(levels));
            if (levels >= BeaconEffects.SECONDARY_LEVELS) {
                allowed.add(primary);
            }
            if (allowed.isEmpty()) {
                throw new ApiError(ErrorKind.BAD_ARGUMENT, "a secondary effect needs " + BeaconEffects.SECONDARY_LEVELS
                        + " pyramid levels; this beacon has " + levels, Call.of("numen.gui.choose", BeaconEffects.name(primary)));
            }
            secondary = effect(second, allowed, "secondary", levels);
        }
        boolean paid = menu.hasPayment();
        her.packets().setBeacon(Optional.of(primary), Optional.ofNullable(secondary));
        boolean set = paid && !menu.hasPayment() && Objects.equals(menu.getPrimaryEffect(), primary)
                && Objects.equals(menu.getSecondaryEffect(), secondary);
        if (!set) {
            throw new ApiError(ErrorKind.FAILED, paid ? "the beacon kept its effects" : "the beacon is not paid: put an "
                    + "iron ingot, gold ingot, emerald, diamond or netherite ingot in its slot first (numen.gui.put)",
                    paid ? Call.of("numen.gui.view") : Call.of("numen.gui.put", "minecraft:iron_ingot"));
        }
        return "set the beacon to " + BeaconEffects.name(primary)
                + (secondary == null ? "" : " with " + BeaconEffects.name(secondary));
    }

    /** 给的名字得是这些里的一个;写成编号、或不在其中当场说能选哪些。 */
    private static Holder<MobEffect> effect(Pick pick, List<Holder<MobEffect>> allowed, String which, int levels) {
        List<String> names = BeaconEffects.names(allowed);
        String hint = Call.of("numen.gui.choose", names.get(0));
        if (pick.name() == null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "a beacon's " + which + " effect is named, not numbered: one of "
                    + String.join(", ", names), hint);
        }
        String wanted = pick.name().startsWith("minecraft:") ? pick.name().substring("minecraft:".length()) : pick.name();
        for (Holder<MobEffect> each : allowed) {
            if (BeaconEffects.name(each).equals(wanted)) {
                return each;
            }
        }
        throw new ApiError(ErrorKind.BAD_ARGUMENT, "this beacon (" + levels + " levels) cannot have " + pick.name()
                + " as its " + which + " effect; it allows " + String.join(", ", names), hint);
    }
}
