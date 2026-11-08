package com.dwinovo.numen.api.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 这条用例的场地不加屏障顶(四面墙照旧),和高版本原版 {@code @GameTest(skyAccess = true)} 一样。这一代的 {@code @GameTest} 没有这一项,
 * 场地的围栏是夹具自己围的({@code StructureChunksMixin}),它按 {@link GameTestSuites#skyAccess} 决定封不封顶。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SkyAccess {
}
