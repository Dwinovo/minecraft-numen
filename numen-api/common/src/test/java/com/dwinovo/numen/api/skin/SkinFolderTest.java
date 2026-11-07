package com.dwinovo.numen.api.skin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 皮肤就是文件夹里的 png,文件名就是皮肤名;客户端与服务端列的是同一份。 */
class SkinFolderTest {

    @Test
    void itListsThePngsByFileNameAndNothingElse(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("b_steve.png"), SkinFixtures.header(64, 64));
        Files.write(dir.resolve("A Alex.PNG"), SkinFixtures.header(64, 64));
        Files.writeString(dir.resolve("notes.txt"), "不是皮肤");
        Files.createDirectories(dir.resolve("sub.png"));

        List<SkinFolder.Entry> entries = new SkinFolder(dir).list();

        assertEquals(List.of("A Alex", "b_steve"), entries.stream().map(SkinFolder.Entry::name).toList(),
                "名字去掉扩展名,按名字排,目录和别的文件不算");
        assertEquals(33, entries.get(0).size());
    }

    @Test
    void aFolderThatIsNotThereYetIsEmpty(@TempDir Path dir) {
        assertTrue(new SkinFolder(dir.resolve("skins")).list().isEmpty());
    }

    @Test
    void readingChecksTheImageAndOnlyKnowsNamesTheFolderLists(@TempDir Path dir) throws IOException {
        byte[] steve = SkinFixtures.header(64, 64);
        Files.write(dir.resolve("steve.png"), steve);
        Files.write(dir.resolve("huge.png"), SkinFixtures.header(512, 512));
        SkinFolder folder = new SkinFolder(dir.resolve("inner"));
        Files.createDirectories(dir.resolve("inner"));
        Files.write(dir.resolve("inner").resolve("ok.png"), steve);

        assertArrayEquals(steve, new SkinFolder(dir).read("steve"));
        assertEquals(SkinError.BAD_IMAGE, assertThrows(SkinError.class, () -> new SkinFolder(dir).read("huge")).code(),
                "尺寸不对的图读的时候报原因");
        assertEquals(SkinError.BAD_IMAGE, assertThrows(SkinError.class, () -> new SkinFolder(dir).read("missing")).code());
        assertEquals(SkinError.BAD_IMAGE, assertThrows(SkinError.class, () -> folder.read("../steve")).code(),
                "远端传来的名字指不到文件夹外面");
        assertArrayEquals(steve, folder.read("ok"));
    }
}
