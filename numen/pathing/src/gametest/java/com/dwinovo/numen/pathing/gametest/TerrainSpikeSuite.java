package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import java.util.List;

/** 真实地形可行性验证(临时):只用 {@code -Pgametest=numen_pathing_terrain} 手动选跑,不在日常批里。 */
public final class TerrainSpikeSuite implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(TerrainSpikeGameTests.class);
    }
}
