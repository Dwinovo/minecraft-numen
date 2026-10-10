package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import java.util.List;

/** 制作真实地形固定存档的套件:只用 {@code -Pgametest=numen_pathing_terrain_bake} 手动选跑,不在日常批里。 */
public final class TerrainBakeSuite implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(TerrainBakeGameTests.class);
    }
}
