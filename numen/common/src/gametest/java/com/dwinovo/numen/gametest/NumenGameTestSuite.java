package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import com.dwinovo.numen.api.gametest.GameTestSuites;
import java.util.List;

/**
 * Numen 的 GameTest 套件:命名空间就是模组 id {@code numen}。日常批({@code numen})收时限不超过 {@link GameTestSuites#FAST_TICKS} 的,
 * 慢批({@link Slow},{@code numen_slow})收超过的;同一份用例类,用例名与结构模板都不因分批而变。
 */
public class NumenGameTestSuite implements GameTestSuite {

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

    /** 用例里同伴的寻路按做了多少活折算想多久:结论与机器快慢无关,与寻路模块自己的用例、靶场同一块表。 */
    public NumenGameTestSuite() {
        com.dwinovo.numen.api.platform.ServerLifecycle.afterStarted(server ->
                com.dwinovo.numen.nav.NavClock.use(server, com.dwinovo.numen.pathing.search.SearchClock.STEADY));
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

    @Override
    public List<java.util.function.Supplier<String>> background() {
        return List.of(com.dwinovo.numen.pathing.search.Searches::busy);
    }
}
