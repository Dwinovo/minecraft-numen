package com.dwinovo.numen.api.gametest;

import java.util.List;

/**
 * 一个模块的一组 GameTest 用例。每个模块(Numen、各联动,或任何用 Numen API 的模组)写自己的用例,用自己的命名空间,
 * 经 {@link java.util.ServiceLoader} 报到({@code META-INF/services/com.dwinovo.numen.api.gametest.GameTestSuite});
 * {@link GameTestSuites} 把选中的套件里的 {@code @GameTest} 方法交给原版的 GameTest 框架。用例本身只用原版的注解。
 */
public interface GameTestSuite {

    /** 套件名,也是这个模块用例的命名空间(测试名、结构模板名的前缀),与模组 id 同一个取法,如 {@code numen_pathing}。 */
    String name();

    /** 这个模块的用例类:类里的 {@code @GameTest}、{@code @BeforeBatch}、{@code @AfterBatch}、{@code @GameTestGenerator} 方法都是静态的。 */
    List<Class<?>> classes();
}
