package com.dwinovo.numen.pathing.plan;

import java.util.List;

/**
 * 端口:此刻要避开的生物,各带自己的危险半径。规划在建成本模型时问一次,把它们变成按位置的代价;模块自己不看实体。
 */
@FunctionalInterface
public interface Threats {

    /** 没有要避开的。 */
    Threats NONE = List::of;

    List<Threat> current();
}
