package com.dwinovo.numen.gametest;

import java.util.List;

/** KubeJS 联动的 GameTest 套件:名字 {@code numen_kubejs},只在挂着 KubeJS 的那一次跑批里。 */
public final class KubeJsGameTestSuite extends CompanionGameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(KubeJsGameTests.class);
    }
}
