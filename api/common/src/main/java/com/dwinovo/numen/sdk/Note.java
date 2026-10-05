package com.dwinovo.numen.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 一条注意:会不会问主人、是不是长活、会动她的什么、不会做什么。可以写多条,按写的顺序列出。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Note.All.class)
public @interface Note {
    String value();

    /** 写了多条时的容器。 */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface All {
        Note[] value();
    }
}
