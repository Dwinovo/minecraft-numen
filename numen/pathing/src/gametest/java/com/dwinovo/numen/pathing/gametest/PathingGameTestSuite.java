package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import java.util.List;

/** 寻路模块的 GameTest 套件:命名空间 {@code numen_pathing},用例只看模块与原版。 */
public final class PathingGameTestSuite implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(
                AlterGameTests.class,
                BlockGameTests.class,
                BreathGameTests.class,
                BudgetGameTests.class,
                ClimbGameTests.class,
                DigGameTests.class,
                DoorGameTests.class,
                DynamicGameTests.class,
                FacadeGameTests.class,
                FlatGameTests.class,
                FluidGameTests.class,
                GoalGameTests.class,
                MaterialGameTests.class,
                ParkourGameTests.class,
                RecheckGameTests.class,
                StairGameTests.class,
                TickRateGameTests.class,
                UpDownGameTests.class
        );
    }
}
