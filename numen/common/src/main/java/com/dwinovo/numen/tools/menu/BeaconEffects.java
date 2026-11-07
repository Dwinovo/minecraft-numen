package com.dwinovo.numen.tools.menu;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 信标按金字塔层数能选哪些效果,和原版客户端 {@code BeaconScreen} 里哪些按钮点得了是同一张表({@code BeaconBlockEntity.BEACON_EFFECTS}):
 * 前三组是主效果,第 {@code i} 组要 {@code i + 1} 层以上;最后一组(再生)是副效果,要 4 层,副效果也可以是和主效果一样的那一个。
 * 原版服务端收 {@code SetBeacon} 时不查层数,所以谁都能发出点不了的按钮对应的包;这里只让她发真客户端点得了的。
 */
final class BeaconEffects {

    /** 副效果要的层数。 */
    static final int SECONDARY_LEVELS = 4;

    private BeaconEffects() {}

    /** 这个层数下能选的主效果,按原版的先后。 */
    static List<Holder<MobEffect>> primaries(int levels) {
        List<Holder<MobEffect>> out = new ArrayList<>();
        for (int tier = 0; tier < Math.min(levels, BeaconBlockEntity.BEACON_EFFECTS.size() - 1); tier++) {
            out.addAll(BeaconBlockEntity.BEACON_EFFECTS.get(tier));
        }
        return out;
    }

    /** 这个层数下除了"和主效果一样"之外能选的副效果。 */
    static List<Holder<MobEffect>> secondaries(int levels) {
        return levels >= SECONDARY_LEVELS ? BeaconBlockEntity.BEACON_EFFECTS.get(BeaconBlockEntity.BEACON_EFFECTS.size() - 1)
                : List.of();
    }

    /** 效果的名字:注册表里的路径,{@code haste}。 */
    static String name(Holder<MobEffect> effect) {
        return effect.unwrapKey().map(key -> key.location().getPath()).orElse("?");
    }

    static List<String> names(List<Holder<MobEffect>> effects) {
        return effects.stream().map(BeaconEffects::name).toList();
    }
}
