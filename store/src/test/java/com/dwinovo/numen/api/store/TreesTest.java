package com.dwinovo.numen.api.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 整个目录树的复制与删除:库里的目录型条目、旧数据的迁移、家的删除共用。 */
class TreesTest {

    private static void put(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void copyingBringsTheWholeTreeAndTheFirstLevelCanBeFiltered(@TempDir Path dir) throws IOException {
        put(dir.resolve("from/a.txt"), "a");
        put(dir.resolve("from/sub/b.txt"), "b");
        put(dir.resolve("from/skip/c.txt"), "c");

        Trees.copy(dir.resolve("from"), dir.resolve("all"));
        Trees.copy(dir.resolve("from"), dir.resolve("some"), name -> !name.equals("skip"));

        assertEquals("b", Files.readString(dir.resolve("all/sub/b.txt")));
        assertTrue(Files.exists(dir.resolve("all/skip/c.txt")));
        assertEquals("a", Files.readString(dir.resolve("some/a.txt")));
        assertFalse(Files.exists(dir.resolve("some/skip")));
    }

    @Test
    void deletingTakesTheTreeAndAMissingOneIsNothing(@TempDir Path dir) throws IOException {
        put(dir.resolve("t/a/b.txt"), "x");

        Trees.delete(dir.resolve("t"));
        Trees.delete(dir.resolve("t"));
        Trees.deleteFile(dir.resolve("t/a/b.txt"));

        assertFalse(Files.exists(dir.resolve("t")));
    }

    @Test
    void emptyParentsGoOneByOneUpToTheStopAndNoFurther(@TempDir Path dir) throws IOException {
        put(dir.resolve("root/skills/tlm/maid/references/a.md"), "a");
        put(dir.resolve("root/skills/numen/x/SKILL.md"), "x");
        Trees.deleteFile(dir.resolve("root/skills/tlm/maid/references/a.md"));

        Trees.deleteEmptyParents(dir.resolve("root/skills/tlm/maid/references"), dir.resolve("root"));

        assertFalse(Files.exists(dir.resolve("root/skills/tlm")), "空的一路清掉");
        assertTrue(Files.exists(dir.resolve("root/skills/numen/x/SKILL.md")), "不空的不动");
        assertTrue(Files.exists(dir.resolve("root")), "停点本身不删");
    }
}
