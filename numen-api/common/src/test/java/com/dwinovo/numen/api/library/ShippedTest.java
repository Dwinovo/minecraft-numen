package com.dwinovo.numen.api.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jar 只负责把内置放进服务器目录,照 dpkg 对配置文件的办法:没被动过的换成新版;管理员改过的留着、新版写在 {@code .new} 里;
 * 管理员删掉的不再放回;来源卸载了,没改过的一起删、改过的留下标"来源已卸载"。清单 {@code .shipped.json} 记每个文件的来源与指纹。
 */
class ShippedTest {

    private static final LibraryKind PERSONA = LibraryKind.files("persona", ".md");
    private static final LibraryKind SKILLS = LibraryKind.directories("skills", "SKILL.md");

    @TempDir
    Path dir;

    private Path jar(String source) {
        return dir.resolve("jar-" + source);
    }

    private Path server() {
        return dir.resolve("server");
    }

    private void ship(String source, String path, String text) throws IOException {
        Path file = jar(source).resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private Shipped.Manifest sync(String... sources) {
        return Shipped.sync(server(), java.util.Arrays.stream(sources)
                .map(source -> new Shipped.Bundle(source, jar(source))).toList());
    }

    private String onDisk(String path) throws IOException {
        return Files.readString(server().resolve(path));
    }

    @Test
    void theFirstSyncPlacesEveryFileUnderTheSameLayoutAndRecordsTheirSource() throws IOException {
        ship("numen", "persona/numen/小焰.md", "傲娇");
        ship("numen", "skills/numen/mining/SKILL.md", "skill");
        ship("numen", "skills/numen/mining/references/ores.md", "ores");
        ship("tlm", "skills/tlm/maid/SKILL.md", "maid");

        sync("numen", "tlm");

        assertEquals("傲娇", onDisk("persona/numen/小焰.md"));
        assertEquals("ores", onDisk("skills/numen/mining/references/ores.md"));
        assertEquals("maid", onDisk("skills/tlm/maid/SKILL.md"));
        String manifest = Files.readString(server().resolve(Shipped.MANIFEST));
        assertTrue(manifest.contains("\"persona/numen/小焰.md\"") && manifest.contains("\"source\": \"tlm\""), manifest);
    }

    @Test
    void anUntouchedFileTakesTheNewVersion() throws IOException {
        ship("numen", "persona/numen/小焰.md", "v1");
        sync("numen");

        ship("numen", "persona/numen/小焰.md", "v2");
        Shipped.Manifest manifest = sync("numen");

        assertEquals("v2", onDisk("persona/numen/小焰.md"));
        assertFalse(Files.exists(server().resolve("persona/numen/小焰.md.new")));
        Provenance provenance = manifest.of(PERSONA, new Shelf.Item(new EntryId("numen", "小焰"), null, "v2"));
        assertFalse(provenance.modified() || provenance.updateAvailable());
    }

    @Test
    void aFileTheAdminChangedIsKeptAndTheNewVersionWaitsNextToItInAnDotNewFile() throws IOException {
        ship("numen", "persona/numen/小焰.md", "v1");
        sync("numen");
        Files.writeString(server().resolve("persona/numen/小焰.md"), "管理员的");

        ship("numen", "persona/numen/小焰.md", "v2");
        Shipped.Manifest manifest = sync("numen");

        assertEquals("管理员的", onDisk("persona/numen/小焰.md"), "留他的");
        assertEquals("v2", onDisk("persona/numen/小焰.md.new"), "新版写在旁边");
        Provenance provenance = manifest.of(PERSONA, new Shelf.Item(new EntryId("numen", "小焰"), null, null));
        assertTrue(provenance.modified() && provenance.updateAvailable());

        sync("numen");   // 再起一次服务器:不变
        assertEquals("管理员的", onDisk("persona/numen/小焰.md"));
        assertEquals("v2", onDisk("persona/numen/小焰.md.new"));
    }

    @Test
    void takingTheNewVersionReplacesHisFileAndDismissingKeepsItAndStopsOfferingThatVersion() throws IOException {
        ship("numen", "persona/numen/小焰.md", "v1");
        sync("numen");
        Files.writeString(server().resolve("persona/numen/小焰.md"), "管理员的");
        ship("numen", "persona/numen/小焰.md", "v2");
        Shipped.Manifest manifest = sync("numen");
        EntryId id = new EntryId("numen", "小焰");

        manifest.dismiss(PERSONA, id);
        assertEquals("管理员的", onDisk("persona/numen/小焰.md"));
        assertFalse(Files.exists(server().resolve("persona/numen/小焰.md.new")));
        sync("numen");
        assertFalse(Files.exists(server().resolve("persona/numen/小焰.md.new")), "不采用的这一版不再提示");

        ship("numen", "persona/numen/小焰.md", "v3");
        manifest = sync("numen");
        assertEquals("v3", onDisk("persona/numen/小焰.md.new"), "下一版再提示");
        manifest.accept(PERSONA, id);
        assertEquals("v3", onDisk("persona/numen/小焰.md"));
        assertFalse(Files.exists(server().resolve("persona/numen/小焰.md.new")));
        Provenance provenance = manifest.of(PERSONA, new Shelf.Item(id, null, null));
        assertFalse(provenance.modified() || provenance.updateAvailable());
    }

    @Test
    void aDirectoryEntryIsTrackedFileByFile() throws IOException {
        ship("numen", "skills/numen/mining/SKILL.md", "v1");
        ship("numen", "skills/numen/mining/references/ores.md", "ores1");
        sync("numen");
        Files.writeString(server().resolve("skills/numen/mining/references/ores.md"), "管理员加了几行");

        ship("numen", "skills/numen/mining/SKILL.md", "v2");
        ship("numen", "skills/numen/mining/references/ores.md", "ores2");
        Shipped.Manifest manifest = sync("numen");

        assertEquals("v2", onDisk("skills/numen/mining/SKILL.md"), "没改过的那个文件换成新版");
        assertEquals("管理员加了几行", onDisk("skills/numen/mining/references/ores.md"));
        assertEquals("ores2", onDisk("skills/numen/mining/references/ores.md.new"));
        Provenance provenance = manifest.of(SKILLS, new Shelf.Item(new EntryId("numen", "mining"), null, null));
        assertTrue(provenance.modified() && provenance.updateAvailable(), "整条目录的来历看其中的文件");
    }

    @Test
    void aFileTheAdminDeletedIsNotPutBack() throws IOException {
        ship("numen", "persona/numen/小焰.md", "v1");
        ship("numen", "persona/numen/团子.md", "v1");
        sync("numen");
        Files.delete(server().resolve("persona/numen/小焰.md"));

        ship("numen", "persona/numen/小焰.md", "v2");
        sync("numen");
        sync("numen");

        assertFalse(Files.exists(server().resolve("persona/numen/小焰.md")), "管理员删的不再放回来");
        assertFalse(Files.exists(server().resolve("persona/numen/小焰.md.new")));
        assertTrue(Files.exists(server().resolve("persona/numen/团子.md")));
    }

    @Test
    void aFileTheAdminPutBackIsTrackedAgain() throws IOException {
        ship("numen", "persona/numen/小焰.md", "v1");
        sync("numen");
        Files.delete(server().resolve("persona/numen/小焰.md"));
        sync("numen");

        Files.writeString(server().resolve("persona/numen/小焰.md"), "v1");
        ship("numen", "persona/numen/小焰.md", "v2");
        sync("numen");

        assertEquals("v2", onDisk("persona/numen/小焰.md"), "他放回来的和上次发的一样,没改过,又跟着换新版");
    }

    @Test
    void aFileTheAdminAlreadyHadWithOtherContentIsNotOverwritten() throws IOException {
        Files.createDirectories(server().resolve("persona/numen"));
        Files.writeString(server().resolve("persona/numen/小焰.md"), "我自己写的");
        ship("numen", "persona/numen/小焰.md", "v1");

        Shipped.Manifest manifest = sync("numen");

        assertEquals("我自己写的", onDisk("persona/numen/小焰.md"));
        assertEquals("v1", onDisk("persona/numen/小焰.md.new"));
        assertTrue(manifest.of(PERSONA, new Shelf.Item(new EntryId("numen", "小焰"), null, null)).modified());
    }

    @Test
    void whenASourceIsGoneItsUntouchedFilesGoAndTheModifiedOnesStayMarked() throws IOException {
        ship("numen", "persona/numen/小焰.md", "v1");
        ship("tlm", "skills/tlm/maid/SKILL.md", "maid");
        ship("tlm", "skills/tlm/maid/references/a.md", "a");
        ship("tlm", "skills/tlm/look/SKILL.md", "look");
        sync("numen", "tlm");
        Files.writeString(server().resolve("skills/tlm/look/SKILL.md"), "管理员改的");

        Shipped.Manifest manifest = sync("numen");   // 车万女仆卸载了

        assertFalse(Files.exists(server().resolve("skills/tlm/maid")), "没改过的一起删,空目录也清掉");
        assertEquals("管理员改的", onDisk("skills/tlm/look/SKILL.md"), "改过的留下");
        Provenance kept = manifest.of(SKILLS, new Shelf.Item(new EntryId("tlm", "look"), null, null));
        assertTrue(kept.sourceGone() && kept.modified());
        assertTrue(Files.exists(server().resolve("persona/numen/小焰.md")));
    }

    @Test
    void aFileASourceNoLongerShipsGoesTheSameWay() throws IOException {
        ship("numen", "persona/numen/小焰.md", "v1");
        ship("numen", "persona/numen/团子.md", "v1");
        sync("numen");

        Files.delete(jar("numen").resolve("persona/numen/团子.md"));
        sync("numen");

        assertFalse(Files.exists(server().resolve("persona/numen/团子.md")));
        assertTrue(Files.exists(server().resolve("persona/numen/小焰.md")));
    }

    @Test
    void anUnreadableManifestIsAnErrorNotAnExcuseToOverwriteEverything() throws IOException {
        ship("numen", "persona/numen/小焰.md", "v1");
        sync("numen");
        Files.writeString(server().resolve(Shipped.MANIFEST), "{broken");

        assertThrows(RuntimeException.class, () -> sync("numen"));
        assertEquals("v1", onDisk("persona/numen/小焰.md"));
    }

    @Test
    void twoSourcesCannotShipTheSameFile() throws IOException {
        ship("numen", "modules/numen/work.lua", "a");
        ship("other", "modules/numen/work.lua", "b");
        ship("other", "modules/other/x.lua", "c");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Shipped.requireDistinct(new Shipped.Bundle("other", jar("other")),
                        List.of(new Shipped.Bundle("numen", jar("numen")))));

        assertTrue(refused.getMessage().contains("modules/numen/work.lua"), refused.getMessage());
        Files.createDirectories(jar("tlm"));
        Shipped.requireDistinct(new Shipped.Bundle("tlm", jar("tlm")), List.of(new Shipped.Bundle("numen", jar("numen"))));
    }
}
