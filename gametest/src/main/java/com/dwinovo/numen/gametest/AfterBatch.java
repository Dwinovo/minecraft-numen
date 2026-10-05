package com.dwinovo.numen.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 一个批次跑完后执行一次:挂在 {@code static void m(ServerLevel)} 上,所在的类要有 {@link NumenTestHolder}。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AfterBatch {

    /** 批次名,同 {@link NumenTest#batch()}。 */
    String batch();
}
