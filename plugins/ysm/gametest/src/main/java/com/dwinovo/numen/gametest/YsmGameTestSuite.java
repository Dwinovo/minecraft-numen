package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import java.util.List;

/** YSM 联动的 GameTest 套件:命名空间 {@code numen_ysm},只在挂着 YSM 的那一次跑批里。 */
public final class YsmGameTestSuite implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(YsmGameTests.class);
    }
}
