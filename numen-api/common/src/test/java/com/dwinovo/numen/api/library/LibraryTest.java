package com.dwinovo.numen.api.library;

import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.Io;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一套库机制:同一布局 {@code <根>/<库>/<命名空间>/<名字>},一种库一个货架,自带的与自己写的在同一份里,每一条能开关。
 * 每种库只声明条目长什么样(一个文件,或一个目录)。
 */
class LibraryTest {

    private static final LibraryKind PERSONA = LibraryKind.files("persona", ".md");
    private static final LibraryKind SKILLS = LibraryKind.directories("skills", "SKILL.md");

    @TempDir
    Path dir;

    private Home home(String name) {
        return new Home(dir.resolve(name), Io.direct(e -> {
            throw e;
        }));
    }

    private Library library(LibraryKind kind, Switches switches) {
        return new Library(kind, new Shelf(kind, home("shelf")), switches, Provenance.Source.NONE);
    }

    private void put(String path, String text) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void aShelfReadsTheLayoutNamespaceThenNameAndIgnoresWhatIsNotAnEntry() throws IOException {
        put("shelf/persona/numen/小焰.md", "傲娇");
        put("shelf/persona/numen/小焰.md.new", "新版");
        put("shelf/persona/numen/note.txt", "不是人设");
        put("shelf/persona/loose.md", "不在命名空间里");
        put("shelf/persona/.hidden/x.md", "点开头的命名空间");

        Shelf shelf = new Shelf(PERSONA, home("shelf"));

        assertEquals(List.of(new EntryId("numen", "小焰")), shelf.items().stream().map(Shelf.Item::id).toList());
        assertEquals("傲娇", shelf.get(new EntryId("numen", "小焰")).text());
        assertEquals("numen:小焰", new EntryId("numen", "小焰").toString());
        assertEquals(new EntryId("numen", "小焰"), EntryId.parse("numen:小焰"));
        assertNull(EntryId.parse("没有冒号"));
    }

    @Test
    void aDirectoryKindTakesOnlyDirectoriesThatHoldTheMarkerFile() throws IOException {
        put("shelf/skills/numen/mining/SKILL.md", "矿");
        put("shelf/skills/numen/mining/references/ores.md", "ores");
        put("shelf/skills/numen/empty/readme.md", "没有 SKILL.md");

        Shelf shelf = new Shelf(SKILLS, home("shelf"));

        assertEquals(List.of(new EntryId("numen", "mining")), shelf.items().stream().map(Shelf.Item::id).toList());
        assertEquals(dir.resolve("shelf/skills/numen/mining"), shelf.get(new EntryId("numen", "mining")).path());
    }

    @Test
    void writingChangesTheTableAtOnceAndLandsOnDiskAndDeletingPrunesTheEmptyNamespace() throws IOException {
        Shelf shelf = new Shelf(PERSONA, home("shelf"));
        long before = shelf.version();

        shelf.write(EntryId.mine("甲"), "一");

        assertEquals("一", shelf.get(EntryId.mine("甲")).text());
        assertEquals("一", Files.readString(dir.resolve("shelf/persona/my/甲.md")));
        assertTrue(shelf.version() > before);

        shelf.delete(EntryId.mine("甲"));
        assertNull(shelf.get(EntryId.mine("甲")));
        assertFalse(Files.exists(dir.resolve("shelf/persona/my")), "空的命名空间目录不留");
        shelf.delete(EntryId.mine("甲"));   // 没有这一条是什么也不做
    }

    @Test
    void handEditsOnDiskAreReadOnlyAfterAReload() throws IOException {
        Shelf shelf = new Shelf(PERSONA, home("shelf"));
        shelf.write(EntryId.mine("甲"), "一");

        Files.writeString(dir.resolve("shelf/persona/my/甲.md"), "手改的");
        assertEquals("一", shelf.get(EntryId.mine("甲")).text());
        shelf.reload();
        assertEquals("手改的", shelf.get(EntryId.mine("甲")).text());
    }

    @Test
    void aFreshNameDoesNotStepOnAnExistingOne() {
        Shelf shelf = new Shelf(PERSONA, home("shelf"));
        shelf.write(EntryId.mine("甲"), "一");
        shelf.write(EntryId.mine("甲_2"), "二");

        assertEquals(EntryId.mine("乙"), shelf.unique(EntryId.MINE, "乙"));
        assertEquals(EntryId.mine("甲_3"), shelf.unique(EntryId.MINE, "甲"));
    }

    @Test
    void namesThatCouldEscapeTheirDirectoryAreNotNames() {
        for (String bad : new String[] {"", " ", ".x", "a/b", "a\\b", "a:b", "..", "a*"}) {
            assertFalse(EntryId.segment(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new EntryId("my", "../etc"));
        assertEquals("名字", EntryId.clean("  名:字/ ", "x"));
        assertEquals("x", EntryId.clean("///", "x"));
        assertEquals("a", EntryId.clean("..a", "x"));
    }

    @Test
    void theSwitchesAreTheOwnersOwnAndAFlippedOffEntryIsStillThereButNotInUse() throws IOException {
        put("shelf/persona/numen/小焰.md", "自带的");
        Library library = library(PERSONA, Switches.inMemory());
        Library theirs = library(PERSONA, Switches.inMemory());

        library.setEnabled(new EntryId("numen", "小焰"), false);

        assertNull(library.find(new EntryId("numen", "小焰")));
        assertFalse(library.findAny(new EntryId("numen", "小焰")).enabled());
        assertTrue(library.active().isEmpty());
        assertEquals(1, library.entries().size(), "列表里还在,可以再打开");
        assertEquals(1, theirs.active().size(), "别人的开关不受影响");
        library.setEnabled(new EntryId("numen", "小焰"), true);
        assertEquals(1, library.active().size());
    }

    @Test
    void theLibraryVersionMovesOnlyWhenFilesDo() throws IOException {
        put("shelf/persona/numen/小焰.md", "自带的");
        Library library = library(PERSONA, Switches.inMemory());
        long start = library.version();

        library.setEnabled(new EntryId("numen", "小焰"), false);
        assertEquals(start, library.version(), "开关不动文件");
        library.shelf().write(EntryId.mine("甲"), "一");
        long written = library.version();
        assertNotEquals(start, written);
        library.reload();
        assertNotEquals(written, library.version());
    }

    @Test
    void theFingerprintOfAFileIsItsContentNotItsName() {
        assertEquals(Fingerprint.of("abc"), Fingerprint.of("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNotEquals(Fingerprint.of("abc"), Fingerprint.of("abd"));
        assertEquals(12, Fingerprint.of("abc").length());
    }

    @Test
    void oneShelfHoldsShippedAndOwnEntriesAndWritesLandInIt() throws IOException {
        put("shelf/persona/numen/小焰.md", "自带的");
        Library library = library(PERSONA, Switches.inMemory());

        library.shelf().write(EntryId.mine("甲"), "我的");

        assertEquals("我的", Files.readString(dir.resolve("shelf/persona/my/甲.md")), "自己写的就写在同一个目录里");
        assertEquals(java.util.Set.of("numen:小焰", "my:甲"),
                library.entries().stream().map(e -> e.id().toString()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("自带的", library.find(new EntryId("numen", "小焰")).text());
        assertEquals("我的", library.find(EntryId.mine("甲")).text());
        assertEquals(library.shelf().version(), library.version());
    }
}
