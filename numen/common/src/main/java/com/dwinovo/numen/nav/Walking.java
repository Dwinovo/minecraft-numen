package com.dwinovo.numen.nav;

import com.dwinovo.numen.pathing.spec.RouteSpec;

/**
 * 走路的规格:模型的 {@code numen.move.to}、{@code away}、{@code follow} 和动手的活替自己走过去都用这一份,只此一处。
 *
 * <p>默认可挖可搭,只看价钱——能走就不挖,绕太远才挖;垫路用出厂的 {@code numen:throwaway} 料({@link ThrowawayBlocks#factory})。
 * 能不能动哪一格不在这里判:权限层说禁止的格规划时是墙,要问的格加价、动手那一刻才问,主人不答应的格这一趟里当墙
 * ({@link Trip})。观察模式下权限层拒一切改动,她自然只走。
 */
public final class Walking {

    private Walking() {}

    /** 走路的规格。 */
    public static RouteSpec spec() {
        return RouteSpec.defaults().edit().changes(true).build();
    }
}
