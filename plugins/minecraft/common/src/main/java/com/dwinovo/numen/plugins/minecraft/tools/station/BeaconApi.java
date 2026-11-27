package com.dwinovo.numen.plugins.minecraft.tools.station;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.Positional;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.sdk.StackRef;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.plugins.minecraft.task.station.StationTaskRecord.Deed;
import com.dwinovo.numen.plugins.minecraft.task.station.StationTaskRecord.Move;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.inventory.BeaconMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code minecraft.beacon}:信标。定它的主效果(和副效果),付一件付款物品。效果用名字({@code haste}),定是 {@code SetBeacon}。
 *
 * <p>按金字塔层数能选哪些效果,和原版客户端信标界面里哪些按钮点得了是同一张表({@code BeaconBlockEntity.BEACON_EFFECTS}):前三组是
 * 主效果,第 {@code i} 组要 {@code i + 1} 层以上;最后一组(再生)是副效果,要 4 层,副效果也可以是和主效果一样的那一个。原版服务端收
 * {@code SetBeacon} 时不查层数,谁都能发出点不了的按钮对应的包;这里只让她发真客户端点得了的。名字不在这张表里,不用走过去就能说;
 * 这座信标的层数许不许,得开了它才知道,所以先看层数再放付款物品——信标界面合上时会把没用掉的付款物品丢在地上。
 */
public final class BeaconApi {

    private static final String KIND = "beacon";
    private static final int[] PAYMENT = {0};

    private BeaconApi() {}

    public static void install(NumenApi numen) {
        numen.api("beacon", "A beacon: set the effect it gives, paying one item.", BeaconApi.class);
    }

    /** 信标定成了什么。 */
    @Doc("What a beacon is set to.")
    public record BeaconPowers(@Doc("Its primary effect, haste.") String primary,
                               @Doc("Its secondary effect, when it has one.") Optional<String> secondary,
                               @Doc("The pyramid levels under it, 1 to 4.") int levels) {}

    /** 哪座信标、付什么、主副效果。 */
    public record Set(@Doc("The beacon's cell.") BlockPos beacon,
                      @Doc("The item to pay with, minecraft:iron_ingot: one of them is used up.") StackRef pay,
                      @Doc("The primary effect by name, haste.") String primary,
                      @Doc("The secondary effect by name: regeneration, or the primary again.")
                      @Omitted("set no secondary effect") @Positional Optional<String> secondary) {}

    @Fn("Set the effect a beacon gives, paying one item you carry.")
    @Example("minecraft.beacon.set({x = 120, y = 64, z = -35}, \"minecraft:iron_ingot\", \"haste\")")
    @Example("minecraft.beacon.set({x = 120, y = 64, z = -35}, \"minecraft:emerald\", \"strength\", \"regeneration\")")
    @Note("Walks up to the beacon when it is out of reach, checks its pyramid allows the effects, puts the payment "
            + "in and sets them.")
    @Note("Returns what the beacon is set to. When its pyramid does not allow an effect it fails before paying, "
            + "saying how many levels it has and which effects those allow.")
    @SeeAlso("minecraft.scan.block")
    public static Pending<BeaconPowers> set(ServerCall call, Set args) {
        Holder<MobEffect> primary = named(args.primary(), BeaconEffects.primaries(BeaconEffects.SECONDARY_LEVELS),
                "primary");
        Holder<MobEffect> secondary = args.secondary().isEmpty() ? null
                : named(args.secondary().get(), secondaries(BeaconEffects.SECONDARY_LEVELS, primary), "secondary");
        AtomicReference<BeaconPowers> set = new AtomicReference<>();
        return Stations.at(call, args.beacon(), BeaconMenu.class, KIND, List.of(
                new Deed((menu, her) -> {
                    allowed((BeaconMenu) menu, primary, secondary);
                    return null;
                }),
                Move.put(args.pay().in(call.her().getInventory(), call.her().registryAccess()), 1, PAYMENT, "payment"),
                new Deed((menu, her) -> {
                    BeaconMenu beacon = (BeaconMenu) menu;
                    her.packets().setBeacon(Optional.of(primary), Optional.ofNullable(secondary));
                    if (beacon.hasPayment() || !Objects.equals(beacon.getPrimaryEffect(), primary)
                            || !Objects.equals(beacon.getSecondaryEffect(), secondary)) {
                        throw new ApiError(ErrorKind.FAILED, "the beacon kept its effects", null);
                    }
                    set.set(new BeaconPowers(BeaconEffects.name(primary),
                            Optional.ofNullable(secondary).map(BeaconEffects::name),
                            beacon.getLevels()));
                    return "set it to " + BeaconEffects.name(primary)
                            + (secondary == null ? "" : " with " + BeaconEffects.name(secondary));
                })), set::get);
    }

    /** 这座信标的层数许不许这两个效果;不许就说它有几层、这几层许哪些。 */
    private static void allowed(BeaconMenu menu, Holder<MobEffect> primary, Holder<MobEffect> secondary) {
        int levels = menu.getLevels();
        if (levels <= 0) {
            throw new ApiError(ErrorKind.FAILED, "this beacon has no pyramid under it (0 levels), so it gives no effect",
                    null);
        }
        if (!BeaconEffects.primaries(levels).contains(primary)) {
            throw new ApiError(ErrorKind.FAILED, "this beacon has " + levels + " pyramid levels, which do not allow "
                    + BeaconEffects.name(primary) + " as its primary effect; they allow "
                    + String.join(", ", BeaconEffects.names(BeaconEffects.primaries(levels))),
                    null);
        }
        if (secondary != null && !secondaries(levels, primary).contains(secondary)) {
            throw new ApiError(ErrorKind.FAILED, "this beacon has " + levels + " pyramid levels; a secondary effect "
                    + "takes " + BeaconEffects.SECONDARY_LEVELS, null);
        }
    }

    /** 名字得是这些效果里的一个({@code minecraft:} 可写可不写);不是当场说有哪些。 */
    private static Holder<MobEffect> named(String name, List<Holder<MobEffect>> among, String which) {
        String wanted = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
        for (Holder<MobEffect> each : among) {
            if (BeaconEffects.name(each).equals(wanted)) {
                return each;
            }
        }
        throw new ApiError(ErrorKind.BAD_ARGUMENT, name + " is not a beacon's " + which + " effect; those are "
                + String.join(", ", BeaconEffects.names(among)), null);
    }

    /** 这个层数下能选的副效果:表里最后一组,加上和主效果一样的那一个;层数不够是空表。 */
    private static List<Holder<MobEffect>> secondaries(int levels, Holder<MobEffect> primary) {
        List<Holder<MobEffect>> out = new ArrayList<>(BeaconEffects.secondaries(levels));
        if (levels >= BeaconEffects.SECONDARY_LEVELS) {
            out.add(primary);
        }
        return out;
    }
}
