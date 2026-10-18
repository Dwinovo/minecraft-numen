package com.dwinovo.numen.api.home;

import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.Trees;

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

    /**
     * 整个目录删掉。删的活排在这条车道的最后:在它前面还没写下去的写先落完,之后这条车道上不再有写。
     * 目录本来就不在是什么也不做。
     */
    public void delete() {
        io.write(() -> Trees.delete(dir));
    }
}
