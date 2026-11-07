package com.dwinovo.numen.api.gametest;

import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Rotation;

/**
 * 一条用例的全部要素:哪一批、叫什么、用哪块结构模板、怎么摆、时限,和用例体。{@link GameTest} 读出来的、{@link GameTestGenerator}
 * 现算出来的都是它;{@link GameTestRegistration} 把它登记成测试实例。
 *
 * @param testName 全名 {@code 套件名.小写类名.小写方法名},铺份的第 2 份起加 {@code #份号}
 */
public record TestFunction(String batchName, String testName, String structureName, Rotation rotation, int maxTicks,
                           long setupTicks, boolean required, boolean skyAccess, Consumer<GameTestHelper> function) {

    /** 不露天的用例。 */
    public TestFunction(String batchName, String testName, String structureName, Rotation rotation, int maxTicks,
                        long setupTicks, boolean required, Consumer<GameTestHelper> function) {
        this(batchName, testName, structureName, rotation, maxTicks, setupTicks, required, false, function);
    }
}
