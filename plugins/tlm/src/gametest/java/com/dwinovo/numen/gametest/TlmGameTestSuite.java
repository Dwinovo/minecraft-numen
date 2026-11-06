package com.dwinovo.numen.gametest;

import java.util.List;

/** 车万女仆联动的 GameTest 套件:命名空间 {@code numen_tlm},只在挂着车万女仆的那一次跑批里。 */
public final class TlmGameTestSuite extends CompanionGameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(TlmGameTests.class);
    }
}
