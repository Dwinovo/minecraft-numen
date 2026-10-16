package com.dwinovo.numen.api.home;

import com.dwinovo.numen.api.store.Io;

import java.nio.file.Path;

/**
 * 一处落盘的地方:一个目录,加上写它用的那条车道({@link Io})。用的一方拿到它就够了,不自己拼路径、不自己挑线程。
 * 目录不保证已存在,写之前由各存储({@code JsonDoc}、{@code AppendLog}、{@code Atomic})自己建。
 */
public record Home(Path dir, Io io) {

    /** 目录里一个文件(或子目录)的路径。 */
    public Path file(String name) {
        return dir.resolve(name);
    }
}
