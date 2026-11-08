package com.dwinovo.numen.api.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 整份文件的可靠写法:先写同目录的临时文件,再原子替换目标。写到一半崩了,目标还是原样,
 * 留下的只有一个下次会被覆盖的临时文件。小文档、日志的整份迁移与重写、一条一个文件的内容文件都走这一条。
 */
public final class Atomic {

    private Atomic() {}

    public static void write(Path file, String text) {
        write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(Path file, byte[] bytes) {
        Path tmp = tmpOf(file);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.write(tmp, bytes);
            swap(tmp, file);
        } catch (IOException e) {
            discard(tmp, e);
            throw new StoreException("写不了 " + file + ": " + e, e);
        }
    }

    /** 整份文件换成 {@code tmp}。文件系统不支持原子移动时退回普通移动:仍是"临时文件换进去",不是原地改。 */
    static void swap(Path tmp, Path dest) throws IOException {
        try {
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Path tmpOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".tmp");
    }

    /** 失败后清掉半截临时文件;清不掉不遮住原来的错,挂在它上面。 */
    static void discard(Path tmp, IOException cause) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            cause.addSuppressed(e);
        }
    }
}
