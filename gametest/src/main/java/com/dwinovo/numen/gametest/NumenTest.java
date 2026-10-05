package com.dwinovo.numen.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 一条游戏内用例:挂在 {@code static void m(GameTestHelper)} 上,所在的类要有 {@link NumenTestHolder}。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NumenTest {

    /** 结构模板名(对应模板目录里的 {@code <template>.snbt})。 */
    String template();

    /** 超时的游戏刻。 */
    int timeoutTicks();

    /** 批次名:同名的用例分在同一批里跑,批次开场与收场的钩子按这个名字找({@link BeforeBatch}、{@link AfterBatch})。 */
    String batch();
}
