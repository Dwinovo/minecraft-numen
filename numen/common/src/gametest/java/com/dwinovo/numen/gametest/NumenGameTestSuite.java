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
                BrainGameTests.class,
                BuildGameTests.class,
                CellRouteGameTests.class,
                ClientlessGameTests.class,
                CollectGameTests.class,
                CombatGameTests.class,
                CommandGameTests.class,
                ConversationCommandGameTests.class,
                ContainerGameTests.class,
                DigGameTests.class,
                FishGameTests.class,
                FloatGameTests.class,
                GearGameTests.class,
                InteractGameTests.class,
                InventoryGameTests.class,
                LibraryCommandGameTests.class,
                LocateGameTests.class,
                ModeGameTests.class,
                MovementGameTests.class,
                PerceptionGameTests.class,
                PermissionGameTests.class,
                PermissionPanelGameTests.class,
                PluginGameTests.class,
                RemoteBrainGameTests.class,
                WalkGameTests.class,
                ScriptGameTests.class,
                SleepGameTests.class,
                SurvivalGameTests.class,
                SwimGameTests.class,
                TaskControlGameTests.class
        );
    }
}
