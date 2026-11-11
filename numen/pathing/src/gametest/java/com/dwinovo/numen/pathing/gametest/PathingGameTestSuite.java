package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.BackgroundSettle;
import com.dwinovo.numen.api.gametest.GameTestSuite;
import com.dwinovo.numen.api.gametest.GameTestSuites;
import java.util.List;

/**
 * 寻路模块的 GameTest 套件:命名空间 {@code numen_pathing},用例只看模块与原版。日常批({@code numen_pathing})收时限不超过
 * {@link GameTestSuites#FAST_TICKS} 的,慢批({@link Slow},{@code numen_pathing_slow})收超过的,里面有可靠性矩阵
 * ({@link MatrixGameTests}):改了寻路的执行或走法前提时跑。同一份用例类,用例名与结构模板都不因分批而变。
 */
public class PathingGameTestSuite implements GameTestSuite {

    /** 慢批:{@code -Pgametest=numen_pathing_slow}。 */
    public static final class Slow extends PathingGameTestSuite {
        @Override
        public Pace pace() {
            return Pace.SLOW;
        }
    }

    /** 一刻当中问到没跑完的搜索时等它回来,与同伴套件同一处({@link com.dwinovo.numen.pathing.search.Searches#onUnfinishedPoll})。 */
    public PathingGameTestSuite() {
        com.dwinovo.numen.pathing.search.Searches.onUnfinishedPoll(BackgroundSettle::await);
    }

    @Override
    public Pace pace() {
        return Pace.FAST;
    }

    @Override
    public List<Class<?>> classes() {
        return List.of(
                AirGameTests.class,
                AlterGameTests.class,
                BlockGameTests.class,
                BreathGameTests.class,
                ClimbGameTests.class,
                DigGameTests.class,
                DoorGameTests.class,
                DynamicGameTests.class,
                FacadeGameTests.class,
                FlatGameTests.class,
                FlightGameTests.class,
                FluidGameTests.class,
                GlideGameTests.class,
                GoalGameTests.class,
                MatrixGameTests.class,
                MaterialGameTests.class,
                ParkourGameTests.class,
                PathTraceGameTests.class,
                RecheckGameTests.class,
                StairGameTests.class,
                TerminationGameTests.class,
                UpDownGameTests.class
        );
    }

    @Override
    public List<java.util.function.Supplier<String>> background() {
        return List.of(com.dwinovo.numen.pathing.search.Searches::busy);
    }
}
