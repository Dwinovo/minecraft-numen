package com.dwinovo.numen.api.combat;

import com.dwinovo.numen.api.agent.scope.Handle;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 远程武器提供者的登记表。先登记的优先:同一件物品几个提供者都认,第一个说了算;同样是"要准备"的两件武器,先登记的提供者那件排前面
 * (弓排在弩前)。登记交回句柄,撤回只撤这一条(control 0014)。线程:任何线程。
 */
public final class RangedWeapons {

    private static final List<RangedWeapon> WEAPONS = new CopyOnWriteArrayList<>();

    private RangedWeapons() {}

    /** 登记一个提供者。<b>Numen API 内部调用</b>,插件经 {@code NumenApi#rangedWeapon}。 */
    public static Handle register(RangedWeapon weapon) {
        WEAPONS.add(weapon);
        return Handle.once(() -> WEAPONS.removeIf(w -> w == weapon));
    }

    /** 认得这件物品的提供者;没有是空。 */
    public static Optional<RangedWeapon> of(ItemStack stack) {
        for (RangedWeapon weapon : WEAPONS) {
            if (weapon.handles(stack)) {
                return Optional.of(weapon);
            }
        }
        return Optional.empty();
    }

    /** 登记顺序。 */
    public static List<RangedWeapon> all() {
        return List.copyOf(WEAPONS);
    }
}
