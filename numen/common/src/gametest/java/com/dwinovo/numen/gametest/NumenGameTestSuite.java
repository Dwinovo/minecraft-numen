package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import java.util.List;

/** Numen 的 GameTest 套件:命名空间就是模组 id {@code numen}。 */
public final class NumenGameTestSuite implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(
                ArriveGameTests.class,
                BodyReportGameTests.class,
                BuildGameTests.class,
                CellRouteGameTests.class,
                CollectGameTests.class,
                CombatGameTests.class,
                CommandGameTests.class,
                ContainerGameTests.class,
                DigGameTests.class,
                FishGameTests.class,
                GearGameTests.class,
                InteractGameTests.class,
                InventoryGameTests.class,
                LocateGameTests.class,
                ModeGameTests.class,
                MovementGameTests.class,
                PerceptionGameTests.class,
                PermissionGameTests.class,
                PluginGameTests.class,
                RouteGameTests.class,
                ScriptGameTests.class,
                SleepGameTests.class,
                SurvivalGameTests.class,
                SwimGameTests.class,
                TaskControlGameTests.class
        );
    }
}
