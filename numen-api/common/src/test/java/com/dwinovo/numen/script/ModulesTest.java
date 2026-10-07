package com.dwinovo.numen.script;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块名两段,路径就是名字:{@code gt/layer.lua} 是 {@code gt.layer}。运行时只读磁盘;出厂的一套在这个进程第一次用到目录时装进去,升级照
 * dpkg 的 conffile:没改过的换成新版,改过的留着并标"出厂有新版",删掉的不再装回,她新建的撞上出厂同名的当作改过,出厂不再发的没改过就
 * 跟着走;{@code reset} 还原。战绩记在目录的账本里,正文换了从头记。名字的规矩只在 {@link Modules};出厂模块登记时把关。
 */
class ModulesTest {

    private static final String BASE = "-- Test base.\nlocal M = {}\n---Say one.\nfunction M.one() return 1 end\nreturn M\n";
    private static final String OLD = BASE.replace("return 1", "return 0");
    private static final String MINE = BASE.replace("Test base.", "Mine.");

    @TempDir
    Path dir;

    private Path file(String name) {
        int dot = name.indexOf('.');
        return dir.resolve(name.substring(0, dot)).resolve(name.substring(dot + 1) + ".lua");
    }

    /** 账本里把一份上次装进去的出厂指纹改成 {@code code} 的:当作上一版出厂发的是它。 */
    private void installedWas(String name, String code) throws IOException {
        JsonObject ledger = JsonParser.parseString(Files.readString(dir.resolve("modules.json"))).getAsJsonObject();
        JsonObject entry = ledger.has(name) ? ledger.getAsJsonObject(name) : new JsonObject();
        entry.addProperty("factory", Modules.fingerprint(code));
        ledger.add(name, entry);
        Files.writeString(dir.resolve("modules.json"), ledger.toString());
    }

    @Test
    void theFactoryModulesAreInstalledIntoTheirFoldersAndOnlyTheDiskIsRead() throws IOException {
        BuiltinModules.register("gt.layer", BASE);
        Modules modules = Modules.at(dir);
        assertEquals(BASE, Files.readString(file("gt.layer")), "装进名字空间那个文件夹");
        assertEquals(Modules.Origin.FACTORY, modules.get("gt.layer").origin());
        Files.writeString(file("gt.layer"), MINE);
        assertEquals(MINE, modules.code("gt.layer"), "运行时只读磁盘");
        assertEquals(Modules.Origin.CHANGED, modules.get("gt.layer").origin());
        assertFalse(modules.get("gt.layer").newer(), "出厂那份没变");
    }

    @Test
    void anUnchangedFileTakesTheNewVersionAndAChangedOneIsKeptAndMarked() throws IOException {
        BuiltinModules.register("gt.up", BASE);
        BuiltinModules.register("gt.kept", BASE);
        Modules modules = Modules.at(dir);
        // 上一版出厂发的是 OLD:一份原样没动,一份她改过
        Files.writeString(file("gt.up"), OLD);
        installedWas("gt.up", OLD);
        Files.writeString(file("gt.kept"), MINE);
        installedWas("gt.kept", OLD);
        modules.install();
        assertEquals(BASE, Files.readString(file("gt.up")), "没改过的换成新版");
        assertEquals(Modules.Origin.FACTORY, modules.get("gt.up").origin());
        assertEquals(MINE, Files.readString(file("gt.kept")), "改过的留着");
        assertTrue(modules.get("gt.kept").newer(), "出厂有新版");
        assertEquals(BASE, modules.reset("gt.kept"));
        assertEquals(BASE, Files.readString(file("gt.kept")));
        assertFalse(modules.get("gt.kept").newer());
    }

    @Test
    void aDeletedFactoryModuleStaysDeletedUntilReset() throws IOException {
        BuiltinModules.register("gt.gone", BASE);
        Modules modules = Modules.at(dir);
        assertEquals(BASE, modules.delete("gt.gone"));
        modules.install();
        assertFalse(Files.exists(file("gt.gone")), "尊重删除,不再装回");
        assertNull(modules.get("gt.gone"));
        assertTrue(modules.deletedFactory("gt.gone"));
        modules.reset("gt.gone");
        assertEquals(BASE, modules.code("gt.gone"), "reset 装回");
    }

    @Test
    void herNewFileThatMeetsANewFactoryModuleCountsAsChanged() throws IOException {
        Files.createDirectories(file("gt.clash").getParent());
        Files.writeString(file("gt.clash"), MINE);
        BuiltinModules.register("gt.clash", BASE);
        Modules modules = Modules.at(dir);
        assertEquals(MINE, Files.readString(file("gt.clash")), "她的那份留着");
        assertEquals(Modules.Origin.CHANGED, modules.get("gt.clash").origin());
        assertTrue(modules.get("gt.clash").newer());
    }

    @Test
    void aModuleTheFactoryNoLongerShipsGoesUnlessSheChangedIt() throws IOException {
        Modules modules = Modules.at(dir);
        Files.createDirectories(file("gt.dropped").getParent());
        Files.writeString(file("gt.dropped"), OLD);
        installedWas("gt.dropped", OLD);
        Files.writeString(file("gt.adopted"), MINE);
        installedWas("gt.adopted", OLD);
        modules.install();
        assertFalse(Files.exists(file("gt.dropped")), "没改过的跟着出厂走");
        assertEquals(Modules.Origin.HERS, modules.get("gt.adopted").origin(), "改过的成了她的");
    }

    @Test
    void savingUnderAFactoryNameChangesItAndHerOwnGoWhereTheirNameSays() throws IOException {
        BuiltinModules.register("gt.saved", BASE);
        Modules modules = Modules.at(dir);
        assertEquals(Modules.Saved.CHANGED, modules.save("gt.saved", MINE));
        assertEquals(Modules.Origin.CHANGED, modules.get("gt.saved").origin());
        assertEquals(Modules.Saved.NEW, modules.save("my.gt_mine", "-- One.\nreturn {}\n"));
        assertTrue(Files.exists(dir.resolve("my").resolve("gt_mine.lua")), "路径就是名字");
        assertEquals(Modules.Origin.HERS, modules.get("my.gt_mine").origin());
        Files.writeString(dir.resolve("my").resolve("gt_mine.lua"), "-- Two.\nreturn {}\n");
        assertEquals("Two.", modules.get("my.gt_mine").summary(), "主人拿编辑器改了,下一次读就是新的");
        Files.writeString(dir.resolve("my").resolve("gt_broken.lua"), "local M = {\n");
        assertTrue(modules.get("my.gt_broken").problem() != null, "读不通的也列出来,说为什么");
        assertTrue(modules.names().contains("my.gt_broken"));
        assertEquals("-- Two.\nreturn {}\n", modules.delete("my.gt_mine"));
        assertNull(modules.get("my.gt_mine"));
    }

    @Test
    void theRecordAddsUpAndStartsOverWhenTheTextChanges() {
        Modules modules = Modules.at(dir);
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
        assertThrows(IllegalArgumentException.class, () -> Modules.at(dir).save("lumber", BASE));
        assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("my", BASE));
        Files.writeString(dir.resolve("stray.lua"), "return {}\n");
        Modules modules = Modules.at(dir);
        assertNull(modules.get("stray"));
        assertFalse(modules.names().contains("stray"));
    }

    @Test
    void theFactoryAloneReadsNoDirectory() {
        BuiltinModules.register("gt.alone", BASE);
        assertEquals(Modules.Origin.FACTORY, Modules.factory().get("gt.alone").origin());
        assertNull(Modules.factory().dir());
        assertThrows(IllegalStateException.class, () -> Modules.factory().save("gt.alone", BASE));
    }

    @Test
    void aFactoryModuleIsCheckedWhenItIsRegistered() {
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
