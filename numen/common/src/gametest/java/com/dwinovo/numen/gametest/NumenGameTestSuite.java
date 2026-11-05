package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuites;
import java.util.List;

/**
 * Numen 的 GameTest 套件:命名空间就是模组 id {@code numen}。日常批({@code numen})收时限不超过 {@link GameTestSuites#FAST_TICKS} 的,
 * 慢批({@link Slow},{@code numen_slow})收超过的;同一份用例类,用例名与结构模板都不因分批而变。测试服的表与等待见
 * {@link CompanionGameTestSuite}。
 */
public class NumenGameTestSuite extends CompanionGameTestSuite {

    /** 慢批:{@code -Pgametest=numen_slow}。 */
    public static final class Slow extends NumenGameTestSuite {
        @Override
        public Pace pace() {
            return Pace.SLOW;
        }
    }

    @Override
    public Pace pace() {
        return Pace.FAST;
    }

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
                ElytraGameTests.class,
                FishGameTests.class,
                FlyGameTests.class,
                FloatGameTests.class,
                GearGameTests.class,
                InteractGameTests.class,
                ItemComponentsGameTests.class,
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
                RespawnGameTests.class,
                WalkGameTests.class,
                ScriptGameTests.class,
                ShapeGameTests.class,
                GeneratorGameTests.class,
                SleepGameTests.class,
                SurvivalGameTests.class,
                SwimGameTests.class,
                TaskControlGameTests.class
        );
    }
}
