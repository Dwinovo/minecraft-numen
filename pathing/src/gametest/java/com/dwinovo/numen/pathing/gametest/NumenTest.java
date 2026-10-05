package com.dwinovo.numen.pathing.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一条游戏内用例的声明。1.21.5 把注解驱动的 gametest 整套删掉了(用例成了 {@code minecraft:test_instance}
 * 注册表里的数据条目),所以自带一套同形状的注解:用例方法的写法不变,由 core 的 {@code NumenGameTests}
 * 经 FML 的注解扫描找到、登记成测试实例。
 *
 * <p>这三枚注解({@link NumenTest}、{@link NumenBeforeBatch}、{@link NumenAfterBatch})住在寻路的 GameTest 源码集里:
 * 它不碰 Numen,寻路用例与 core 的用例都只对着它写,core 的登记处依赖它而不是反过来。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NumenTest {

    /** 结构模板名(对应 {@code gameteststructures/data/numen/structure/<template>.snbt})。 */
    String template();

    /** 超时游戏刻。 */
    int timeoutTicks();

    /** 批次名:同名批次的用例分在同一批里跑,共用 {@link NumenBeforeBatch} / {@link NumenAfterBatch}。 */
    String batch();
}
