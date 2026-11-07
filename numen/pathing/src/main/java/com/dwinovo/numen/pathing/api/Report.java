package com.dwinovo.numen.pathing.api;

import java.util.List;

import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.pathing.drive.DiveLog;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.drive.Journal;

/**
 * 一次导航交出的行程报告:结局({@code status})、用了几刻、一路上搜了几次/重搜了几次/走不下去几步/卡住几步/复核几次
 * ({@link Journal})、实际走完的每一步按走法分与改了世界的哪几格({@link EditLedger},只收真实结果)、身体为走路做的动作
 * (下载具、把东西拿到手上、创造模式取料),以及身体真在水下憋过的每一段({@link DiveLog})。到了、收场、被叫停都交出它;
 * 还在走时交出的是到此刻为止的。账和计数都记在事件发生的那一处,与写日志的是同一处。
 */
public record Report(NavStatus status, int ticks, Journal journal, EditLedger ledger, List<BodyAction> actions,
                     List<DiveLog.Dive> dives) {

    public Report {
        actions = List.copyOf(actions);
        dives = List.copyOf(dives);
    }
}
