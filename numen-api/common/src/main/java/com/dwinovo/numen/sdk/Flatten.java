package com.dwinovo.numen.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 写在 record 的第一个组件上(它本身是一个有类名的 record):它的字段摊进这一张表,它的类是这个类的父类——一只女仆是一只实体
 * 再加她的工作与设置({@code ---@class tlm.Maid: Entity})。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Flatten {
}
