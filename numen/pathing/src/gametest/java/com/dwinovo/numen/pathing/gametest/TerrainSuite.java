package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import java.util.List;

/** 固定存档上的真实地形路线:只用 {@code -Pgametest=numen_pathing_terrain} 手动选跑,不在日常批里。 */
public final class TerrainSuite implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(TerrainGameTests.class);
    }
}
