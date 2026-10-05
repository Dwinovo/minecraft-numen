package com.dwinovo.numen.pathing.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 批次收尾:这一批的用例都收场后调一次,方法形如 {@code static void m(ServerLevel)}。
 * 对应 26.1 测试环境({@code TestEnvironmentDefinition})的 {@code teardown}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NumenAfterBatch {

    /** 管哪个批次({@link NumenTest#batch()})。 */
    String batch();
}
