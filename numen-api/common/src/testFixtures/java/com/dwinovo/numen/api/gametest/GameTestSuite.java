package com.dwinovo.numen.api.gametest;

import java.util.List;

/**
 * 一个模块的一组 GameTest 用例。每个模块(Numen、各联动,或任何用 Numen API 的模组)写自己的用例,用自己的命名空间,
 * 套件名就是命名空间(测试名、结构模板名的前缀),与模组 id 同一个取法,如 {@code numen_pathing}。套件按名字报到:模块在自己的
 * gametest 资源里放 {@code META-INF/numen/gametest/套件名},内容是实现这个接口的类的全名(要有无参构造)。
 * {@link GameTestSuites} 把运行配置选中的套件里的 {@code @GameTest} 方法交给原版的 GameTest 框架。用例本身只用原版的注解。
 */
public interface GameTestSuite {

    /** 这个模块的用例类:类里的 {@code @GameTest}、{@code @BeforeBatch}、{@code @AfterBatch}、{@code @GameTestGenerator} 方法都是静态的。 */
    List<Class<?>> classes();

    /**
     * 这个模块的用例会用到的、跑在服务器主线程之外的后台工作(Numen API 自己的程序已经算在内):每一项答"此刻还没停下的是什么",
     * 停下了答 {@code null}。每个服务器刻开头都要等它们全停下才往下走,结果到得早晚就和机器快慢无关({@link BackgroundSettle})。
     */
    default List<java.util.function.Supplier<String>> background() {
        return List.of();
    }
}
