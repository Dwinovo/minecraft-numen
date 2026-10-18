package com.dwinovo.numen.api.script;

import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryHub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块是一种库:名字两段,路径就是名字({@code gt/layer.lua} 是 {@code gt.layer})。她看到内置(服务器目录)与她的("我的")叠在一起,
 * 同名时她的优先;她存一份和内置同名的,写进主人的目录,不碰服务器的;{@code reset} 删掉她的那份,内置的露出来;她"删"一份内置的,
 * 是对她关掉它。战绩记在主人目录的账本里,正文换了从头记。名字的规矩只在 {@link Modules};内置模块登记时把关。
 */
class ModulesTest {

    private static final String BASE = "-- Test base.\nlocal M = {}\n---Say one.\nfunction M.one() return 1 end\nreturn M\n";
    private static final String NEWER = BASE.replace("return 1", "return 2");
    private static final String MINE = BASE.replace("Test base.", "Mine.");
    private static final UUID OWNER = UUID.randomUUID();

    @TempDir
    Path dir;

    private LibraryHub hub;

    private Modules modules() {
        hub = LibraryHub.headless(dir);
        return Modules.at(hub.library(Modules.KIND, OWNER));
    }

    /** 服务器目录里的一份内置(同步进去的那种)。 */
    private void builtin(String name, String code) {
        int dot = name.indexOf('.');
        hub.builtin(Modules.KIND).write(new EntryId(name.substring(0, dot), name.substring(dot + 1)), code);
    }

    private Path server(String name) {
        int dot = name.indexOf('.');
        return dir.resolve("server").resolve("modules").resolve(name.substring(0, dot))
                .resolve(name.substring(dot + 1) + ".lua");
    }

    private Path mine(String name) {
        int dot = name.indexOf('.');
        return dir.resolve("players").resolve(OWNER.toString()).resolve("modules").resolve(name.substring(0, dot))
                .resolve(name.substring(dot + 1) + ".lua");
    }

    @Test
    void aBuiltinModuleIsReadFromTheServerDirectoryAndIsNotHers() {
        Modules modules = modules();
        builtin("gt.layer", BASE);

        assertEquals(BASE, modules.code("gt.layer"));
        assertEquals(Modules.Origin.FACTORY, modules.get("gt.layer").origin());
        assertTrue(modules.names().contains("gt.layer"));
        assertFalse(Files.exists(mine("gt.layer")), "内置的不复制到她的目录里");
    }

    @Test
    void savingUnderABuiltinNameChangesItForHerAndLeavesTheServerDirectoryAlone() throws IOException {
        Modules modules = modules();
        builtin("gt.layer", BASE);

        assertEquals(Modules.Saved.CHANGED, modules.save("gt.layer", MINE));

        assertEquals(MINE, modules.code("gt.layer"), "程序用的是她改过的");
        assertEquals(Modules.Origin.CHANGED, modules.get("gt.layer").origin());
        assertFalse(modules.get("gt.layer").newer(), "内置那份没变");
        assertEquals(MINE, Files.readString(mine("gt.layer")), "写进主人的目录");
        assertEquals(BASE, Files.readString(server("gt.layer")), "服务器目录原样");
    }

    @Test
    void resetDropsHerCopyAndTheBuiltinShowsThroughAgain() {
        Modules modules = modules();
        builtin("gt.layer", BASE);
        modules.save("gt.layer", MINE);

        assertEquals(BASE, modules.reset("gt.layer"));

        assertEquals(Modules.Origin.FACTORY, modules.get("gt.layer").origin());
        assertEquals(BASE, modules.code("gt.layer"));
        assertFalse(Files.exists(mine("gt.layer")));
        assertNull(modules.reset("gt.nothing"), "没有内置的那一份没东西可还原");
    }

    @Test
    void aBuiltinThatChangedAfterSheChangedHersIsMarkedNewer() {
        Modules modules = modules();
        builtin("gt.layer", BASE);
        modules.save("gt.layer", MINE);

        builtin("gt.layer", NEWER);

        assertTrue(modules.get("gt.layer").newer(), "内置有新版,她这份是改过的,没跟着换");
        modules.reset("gt.layer");
        assertEquals(NEWER, modules.code("gt.layer"));
        assertFalse(modules.get("gt.layer").newer());
    }

    @Test
    void deletingABuiltinSwitchesItOffForHerUntilReset() throws IOException {
        Modules modules = modules();
        builtin("gt.gone", BASE);

        assertEquals(BASE, modules.delete("gt.gone"));

        assertNull(modules.get("gt.gone"));
        assertTrue(modules.deletedFactory("gt.gone"));
        assertTrue(Files.exists(server("gt.gone")), "服务器上的内置文件不动");
        assertEquals(BASE, modules.reset("gt.gone"));
        assertEquals(BASE, modules.code("gt.gone"), "reset 打开");
        assertFalse(modules.deletedFactory("gt.gone"));
    }

    @Test
    void savingOverADeletedBuiltinNameBringsHerCopyBack() {
        Modules modules = modules();
        builtin("gt.gone", BASE);
        modules.delete("gt.gone");

        modules.save("gt.gone", MINE);

        assertEquals(MINE, modules.code("gt.gone"));
    }

    @Test
    void herOwnModulesGoWhereTheirNameSaysAndAreReadAfterAReload() throws IOException {
        Modules modules = modules();

        assertEquals(Modules.Saved.NEW, modules.save("my.gt_mine", "-- One.\nreturn {}\n"));
        assertEquals(Modules.Origin.HERS, modules.get("my.gt_mine").origin());
        assertTrue(Files.exists(mine("my.gt_mine")), "路径就是名字");

        Files.writeString(mine("my.gt_mine"), "-- Two.\nreturn {}\n");
        assertEquals("One.", modules.get("my.gt_mine").summary(), "手改磁盘上的文件要 /reload 才读到");
        modules.reload();
        assertEquals("Two.", modules.get("my.gt_mine").summary());

        Files.writeString(mine("my.gt_broken"), "local M = {\n");
        modules.reload();
        assertTrue(modules.get("my.gt_broken").problem() != null, "读不通的也列出来,说为什么");
        assertTrue(modules.names().contains("my.gt_broken"));
        assertEquals("-- Two.\nreturn {}\n", modules.delete("my.gt_mine"));
        assertNull(modules.get("my.gt_mine"));
    }

    @Test
    void theRecordAddsUpAndStartsOverWhenTheTextChanges() {
        Modules modules = modules();
        modules.save("my.gt_rec", "-- Rec.\nreturn {}\n");
        modules.tally("my.gt_rec", true, 0, null, 1000L);
        modules.tally("my.gt_rec", false, 3, "out of reach", 2000L);
        assertEquals(new Modules.Stats(2, 1, 2000L, 3, "out of reach"), modules.stats("my.gt_rec"));
        assertEquals(Modules.Saved.REPLACED, modules.save("my.gt_rec", "-- Rec again.\nreturn {}\n"));
        assertEquals(0, modules.stats("my.gt_rec").runs(), "旧战绩说的是旧正文");
    }

    /** 名字的规矩:两段,每段写得出来;名字空间不撞语言与引擎的全局。不在任何名字空间里的文件不是模块。 */
    @Test
    void aModuleNameIsANamespaceAndAGroup() throws IOException {
        assertNull(Modules.problem("my.lumber"));
        assertNull(Modules.problem("my.string"), "组那一段不撞全局");
        assertNull(Modules.problem("numen.work"));
        assertTrue(Modules.problem("lumber") != null, "一段的不是模块名");
        assertTrue(Modules.problem("my.end") != null, "关键字写不出来");
        assertTrue(Modules.problem("my.Lumber") != null);
        assertTrue(Modules.problem("string.x") != null);
        Modules modules = modules();
        assertThrows(IllegalArgumentException.class, () -> modules.save("lumber", BASE));
        assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("my", BASE));
        Files.createDirectories(mine("my.x").getParent());
        Files.writeString(mine("my.x").getParent().getParent().resolve("stray.lua"), "return {}\n");
        modules.reload();
        assertNull(modules.get("stray"));
        assertFalse(modules.names().contains("stray"));
    }

    @Test
    void theShippedSetAloneReadsNoLibrary() {
        BuiltinModules.register("gt.alone", BASE);
        assertEquals(Modules.Origin.FACTORY, Modules.factory().get("gt.alone").origin());
        assertNull(Modules.factory().dir());
        assertThrows(IllegalStateException.class, () -> Modules.factory().save("gt.alone", BASE));
    }

    @Test
    void aShippedModuleIsCheckedWhenItIsRegistered() {
        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class, () -> {
            BuiltinModules.register("gt.twice", BASE);
            BuiltinModules.register("gt.twice", BASE);
        });
        assertTrue(twice.getMessage().contains("登记了两次"), twice.getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("gt.broken_b",
                "-- Broken.\nlocal x = = 1")).getMessage().contains("gt.broken_b:2:"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("gt.dash-x", BASE))
                .getMessage().contains("名字不行"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("string.x", BASE))
                .getMessage().contains("名字不行"));
    }
}
