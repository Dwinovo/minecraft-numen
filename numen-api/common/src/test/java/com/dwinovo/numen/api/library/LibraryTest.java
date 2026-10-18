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
 * 一套库机制:同一布局 {@code <根>/<库>/<命名空间>/<名字>},玩家看到"内置"(服务器目录)与"我的"(玩家目录)两组,同名时我的优先,
 * 玩家对内置只读、能开关、能复制到我的。每种库只声明条目长什么样(一个文件,或一个目录)。
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
        return new Library(kind, new Shelf(kind, home("server")), new Shelf(kind, home("mine")), switches,
                Provenance.Source.NONE);
    }

    private void put(String path, String text) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void aShelfReadsTheLayoutNamespaceThenNameAndIgnoresWhatIsNotAnEntry() throws IOException {
        put("server/persona/numen/小焰.md", "傲娇");
        put("server/persona/numen/小焰.md.new", "新版");
        put("server/persona/numen/note.txt", "不是人设");
        put("server/persona/loose.md", "不在命名空间里");
        put("server/persona/.hidden/x.md", "点开头的命名空间");

        Shelf shelf = new Shelf(PERSONA, home("server"));

        assertEquals(List.of(new EntryId("numen", "小焰")), shelf.items().stream().map(Shelf.Item::id).toList());
        assertEquals("傲娇", shelf.get(new EntryId("numen", "小焰")).text());
        assertEquals("numen:小焰", new EntryId("numen", "小焰").toString());
        assertEquals(new EntryId("numen", "小焰"), EntryId.parse("numen:小焰"));
        assertNull(EntryId.parse("没有冒号"));
    }

    @Test
    void aDirectoryKindTakesOnlyDirectoriesThatHoldTheMarkerFile() throws IOException {
        put("server/skills/numen/mining/SKILL.md", "矿");
        put("server/skills/numen/mining/references/ores.md", "ores");
        put("server/skills/numen/empty/readme.md", "没有 SKILL.md");

        Shelf shelf = new Shelf(SKILLS, home("server"));

        assertEquals(List.of(new EntryId("numen", "mining")), shelf.items().stream().map(Shelf.Item::id).toList());
        assertEquals(dir.resolve("server/skills/numen/mining"), shelf.get(new EntryId("numen", "mining")).path());
    }

    @Test
    void writingChangesTheTableAtOnceAndLandsOnDiskAndDeletingPrunesTheEmptyNamespace() throws IOException {
        Shelf shelf = new Shelf(PERSONA, home("mine"));
        long before = shelf.version();

        shelf.write(EntryId.mine("甲"), "一");

        assertEquals("一", shelf.get(EntryId.mine("甲")).text());
        assertEquals("一", Files.readString(dir.resolve("mine/persona/my/甲.md")));
        assertTrue(shelf.version() > before);

        shelf.delete(EntryId.mine("甲"));
        assertNull(shelf.get(EntryId.mine("甲")));
        assertFalse(Files.exists(dir.resolve("mine/persona/my")), "空的命名空间目录不留");
        shelf.delete(EntryId.mine("甲"));   // 没有这一条是什么也不做
    }

    @Test
    void handEditsOnDiskAreReadOnlyAfterAReload() throws IOException {
        Shelf shelf = new Shelf(PERSONA, home("mine"));
        shelf.write(EntryId.mine("甲"), "一");

        Files.writeString(dir.resolve("mine/persona/my/甲.md"), "手改的");
        assertEquals("一", shelf.get(EntryId.mine("甲")).text());
        shelf.reload();
        assertEquals("手改的", shelf.get(EntryId.mine("甲")).text());
    }

    @Test
    void aFreshNameDoesNotStepOnAnExistingOne() {
        Shelf shelf = new Shelf(PERSONA, home("mine"));
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
    void thePlayerSeesTwoGroupsAndMineWinsOnTheSameName() throws IOException {
        put("server/persona/numen/小焰.md", "内置的小焰");
        put("server/persona/numen/团子.md", "内置的团子");
        put("mine/persona/my/阿光.md", "我的阿光");
        Library library = library(PERSONA, Switches.inMemory());
        library.mine().write(new EntryId("numen", "小焰"), "我改的小焰");

        assertEquals(2, library.group(Library.Group.BUILTIN).size());
        assertEquals(2, library.group(Library.Group.MINE).size());
        List<Library.Entry> effective = library.effective();
        assertEquals(3, effective.size(), "同名只留我的");
        Library.Entry xiaoyan = library.find(new EntryId("numen", "小焰"));
        assertEquals(Library.Group.MINE, xiaoyan.group());
        assertEquals("我改的小焰", xiaoyan.text());
        assertTrue(xiaoyan.paired());
        assertTrue(library.group(Library.Group.BUILTIN).stream()
                .filter(e -> e.id().name().equals("小焰")).findFirst().orElseThrow().paired(), "内置那条被盖着");
        assertFalse(library.find(new EntryId("numen", "团子")).paired());
    }

    @Test
    void copyingABuiltinEntryToMineKeepsTheNameSoMineCoversIt() throws IOException {
        put("server/persona/numen/小焰.md", "内置的");
        Library library = library(PERSONA, Switches.inMemory());

        Library.Entry copy = library.copyToMine(new EntryId("numen", "小焰"));

        assertEquals(Library.Group.MINE, copy.group());
        assertEquals("内置的", Files.readString(dir.resolve("mine/persona/numen/小焰.md")));
        assertEquals("内置的", Files.readString(dir.resolve("server/persona/numen/小焰.md")), "内置的原样");
        assertNull(library.copyToMine(new EntryId("numen", "没有")));
    }

    @Test
    void copyingADirectoryEntryCopiesTheWholeDirectory() throws IOException {
        put("server/skills/numen/mining/SKILL.md", "矿");
        put("server/skills/numen/mining/references/ores.md", "ores");
        Library library = library(SKILLS, Switches.inMemory());

        library.copyToMine(new EntryId("numen", "mining"));
        assertEquals("ores", Files.readString(dir.resolve("mine/skills/numen/mining/references/ores.md")));

        // 再复制一次:换成内置现在的样子,不夹带她删掉的旧文件
        Files.delete(dir.resolve("server/skills/numen/mining/references/ores.md"));
        library.builtin().reload();
        library.copyToMine(new EntryId("numen", "mining"));
        assertFalse(Files.exists(dir.resolve("mine/skills/numen/mining/references/ores.md")));
    }

    @Test
    void theSwitchesAreThePlayersOwnAndAFlippedOffEntryIsStillThereButNotInUse() throws IOException {
        put("server/persona/numen/小焰.md", "内置的");
        Switches mine = Switches.inMemory();
        Switches other = Switches.inMemory();
        Library library = library(PERSONA, mine);
        Library theirs = library(PERSONA, other);

        library.setEnabled(new EntryId("numen", "小焰"), false);

        assertNull(library.find(new EntryId("numen", "小焰")));
        assertFalse(library.findAny(new EntryId("numen", "小焰")).enabled());
        assertTrue(library.active().isEmpty());
        assertEquals(1, library.effective().size(), "列表里还在,可以再打开");
        assertEquals(1, theirs.active().size(), "别人的开关不受影响");
        library.setEnabled(new EntryId("numen", "小焰"), true);
        assertEquals(1, library.active().size());
    }

    @Test
    void theLibraryVersionMovesOnlyWhenFilesDo() throws IOException {
        put("server/persona/numen/小焰.md", "内置的");
        Library library = library(PERSONA, Switches.inMemory());
        long start = library.version();

        library.setEnabled(new EntryId("numen", "小焰"), false);
        assertEquals(start, library.version(), "开关不动文件");
        library.mine().write(EntryId.mine("甲"), "一");
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
}
