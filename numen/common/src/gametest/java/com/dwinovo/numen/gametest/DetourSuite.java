package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import java.util.List;

/** 绕路的代价,从入口量:只用 {@code -Pgametest=numen_detour} 手动选跑,不在日常批里。读数与用法见 {@link DetourGameTests}。 */
public final class DetourSuite implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(DetourGameTests.class);
    }
}
