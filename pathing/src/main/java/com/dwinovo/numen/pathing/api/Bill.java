package com.dwinovo.numen.pathing.api;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.search.Route;

import net.minecraft.core.BlockPos;

/**
 * 账单:预算账(一条候选路线打算怎么走)与实际账(真走了之后改了什么)同一个格式——几步、挖哪几格、放哪几格、
 * 哪几格要主人同意。
 *
 * @param steps    步数
 * @param digs     挖掉的格,按先后
 * @param places   放了方块的格,按先后(放进高草那一格,记的就是那一格)
 * @param consents 其中许可答"要问"的格,连同许可给的凭据
 */
public record Bill(int steps, List<BlockPos> digs, List<BlockPos> places, List<Consent> consents) {

    /** 一格要主人同意;{@code credential} 是许可给的凭据,原样交还。 */
    public record Consent(BlockPos pos, Object credential) {}

    public Bill {
        digs = List.copyOf(digs);
        places = List.copyOf(places);
        consents = List.copyOf(consents);
    }

    /** 一条候选路线的预算账。 */
    public static Bill of(Route route) {
        List<BlockPos> digs = new ArrayList<>();
        List<BlockPos> places = new ArrayList<>();
        List<Consent> consents = new ArrayList<>();
        for (Edit edit : route.edits()) {
            Permit permit = switch (edit) {
                case Edit.Dig dig -> {
                    digs.add(dig.pos());
                    yield dig.permit();
                }
                case Edit.Place place -> {
                    places.add(place.pos());
                    yield place.permit();
                }
                // 接住坠落的那桶水也是往那一格放了东西,落定后收回
                case Edit.Catch caught -> {
                    places.add(caught.pos());
                    yield caught.permit();
                }
                case Edit.Door door -> null;
            };
            if (permit instanceof Permit.Ask ask) {
                consents.add(new Consent(edit.pos(), ask.credential()));
            }
        }
        return new Bill(route.legs().size(), digs, places, consents);
    }

    /** 实际账:只看 {@link EditLedger} 里真挖掉、真放下的。 */
    public static Bill of(EditLedger ledger) {
        List<BlockPos> digs = new ArrayList<>();
        List<BlockPos> places = new ArrayList<>();
        List<Consent> consents = new ArrayList<>();
        for (EditLedger.Entry entry : ledger.entries()) {
            switch (entry) {
                case EditLedger.Dug dug -> digs.add(dug.pos());
                case EditLedger.Placed placed -> places.add(placed.pos());
                case EditLedger.Toggled toggled -> {
                    continue;
                }
            }
            if (entry.permit() instanceof Permit.Ask ask) {
                consents.add(new Consent(entry.pos(), ask.credential()));
            }
        }
        return new Bill(ledger.steps(), digs, places, consents);
    }
}
