package com.dwinovo.numen.pathing.api;

import java.util.List;

import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.drive.EditLedger;

/**
 * 一次导航交出的实际账:改了世界的哪几格({@link EditLedger},只收真实结果),以及身体为走路做的动作(下载具、把东西拿到
 * 手上、创造模式取料)。到了、收场、被叫停都交出它。
 */
public record Report(EditLedger ledger, List<BodyAction> actions) {

    public Report {
        actions = List.copyOf(actions);
    }

    /** 实际账写成账单的格式。 */
    public Bill bill() {
        return Bill.of(ledger);
    }
}
