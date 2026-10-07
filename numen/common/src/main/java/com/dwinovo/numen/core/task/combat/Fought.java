package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.sdk.Doc;

import java.util.List;

/**
 * {@code numen.fight.attack} 收尾时交回的:经手过的每一只怎样了、一共出手几下。
 *
 * @param fought  经手过的每一只,点名的那一只在最前
 * @param strikes 一共出手几下
 */
@Doc("What an attack did: each entity it fought and how that ended.")
public record Fought(@Doc("Each entity it fought, the one you named first.") List<Foe> fought,
                     @Doc("Hits in all.") int strikes) {

    /** 经手过的一只。 */
    @Doc("One entity an attack fought.")
    public record Foe(@Doc("Its runtime id.") int id,
                      @Doc("defeated, lost, unreachable, refused: <why>, or pending.") String status,
                      @Doc("Hits it took from you.") int strikes) {}
}
