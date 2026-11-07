package com.dwinovo.numen.api.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 一批收场后做的善后,挂在 {@code public static void m(ServerLevel)} 上;由这一批的测试环境在 {@code teardown} 里调用。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AfterBatch {

    /** 给哪一批善后(同 {@link GameTest#batch()})。 */
    String batch();
}
