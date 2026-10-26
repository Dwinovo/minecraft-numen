package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import java.util.List;

/** FTB Quests 联动的 GameTest 套件:名字 {@code numen_ftbquests},只在挂着 FTB Quests 的那一次跑批里。 */
public final class FtbQuestsGameTestSuite implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(FtbQuestsGameTests.class);
    }
}

