package com.dwinovo.numen.script;

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
 * 模块名两段,路径就是名字:{@code gt/layer.lua} 是 {@code gt.layer}。内置的在 jar 里,她目录里同一路径的文件盖住它,删掉回到内置;
 * 盖住时记下内置那份的指纹,内置后来变了标出来;每次读都读磁盘上此刻的文件;战绩记在目录的账本里,正文换了从头记。名字的规矩只在
 * {@link Modules};内置模块登记时把关。
 */
class ModulesTest {

    private static final String BASE = "-- Test base.\nlocal M = {}\n---Say one.\nfunction M.one() return 1 end\nreturn M\n";

    @TempDir
    Path dir;

    @Test
    void herFileOverridesTheBuiltinAndDeletingItGivesTheBuiltinBack() {
        BuiltinModules.register("gt.layer", BASE);
        Modules modules = Modules.at(dir);
        assertEquals(Modules.Origin.BUILTIN, modules.get("gt.layer").origin());
        assertEquals(BASE, modules.code("gt.layer"));

        String mine = "-- Mine.\nlocal M = {}\n---Say two.\nfunction M.one() return 2 end\nreturn M\n";
        assertEquals(Modules.Saved.OVERRODE, modules.save("gt.layer", mine));
        assertTrue(Files.exists(dir.resolve("gt").resolve("layer.lua")), "路径就是名字");
        assertEquals(Modules.Origin.OVERRIDE, modules.get("gt.layer").origin());
        assertEquals(mine, modules.code("gt.layer"));
        assertFalse(modules.get("gt.layer").builtinNewer());

        assertEquals(mine, modules.delete("gt.layer"));
        assertEquals(Modules.Origin.BUILTIN, modules.get("gt.layer").origin(), "删掉她那份就回到内置");
        assertNull(modules.delete("gt.layer"), "内置的没有她的那份可删");
    }

    @Test
    void aBuiltinThatChangedSinceSheOverrodeItIsMarked() throws IOException {
        BuiltinModules.register("gt.newer", BASE);
        Modules modules = Modules.at(dir);
        modules.save("gt.newer", BASE.replace("Test base.", "Mine."));
        // 内置那份后来变了:账本里记的指纹对不上
        String ledger = Files.readString(dir.resolve("modules.json"));
        Files.writeString(dir.resolve("modules.json"), ledger.replace(Modules.fingerprint(BASE), "000000000000"));
        assertTrue(modules.get("gt.newer").builtinNewer());
    }

    @Test
    void eachReadReadsTheFileAsItIsNow() throws IOException {
        Modules modules = Modules.at(dir);
        assertEquals(Modules.Saved.NEW, modules.save("my.gt_mine", "-- One.\nreturn {}\n"));
        assertTrue(Files.exists(dir.resolve("my").resolve("gt_mine.lua")), "路径就是名字");
        Files.writeString(dir.resolve("my").resolve("gt_mine.lua"), "-- Two.\nreturn {}\n");
        assertEquals("Two.", modules.get("my.gt_mine").summary(), "主人拿编辑器改了,下一次读就是新的");
        Files.writeString(dir.resolve("my").resolve("gt_broken.lua"), "local M = {\n");
        assertTrue(modules.get("my.gt_broken").problem() != null, "读不通的也列出来,说为什么");
        assertTrue(modules.names().contains("my.gt_broken"));
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

    /** 名字的规矩:两段,每段写得出来;名字空间不撞语言与引擎的全局。不合规矩的文件不算模块。 */
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
        // 顶层一个文件不在任何名字空间里:不是模块
        Files.writeString(dir.resolve("stray.lua"), "return {}\n");
        Modules modules = Modules.at(dir);
        assertNull(modules.get("stray"));
        assertFalse(modules.names().contains("stray"));
    }

    @Test
    void theBuiltinLayerAloneReadsNoDirectory() {
        BuiltinModules.register("gt.alone", BASE);
        assertEquals(Modules.Origin.BUILTIN, Modules.builtin().get("gt.alone").origin());
        assertNull(Modules.builtin().dir());
        assertThrows(IllegalStateException.class, () -> Modules.builtin().save("gt.alone", BASE));
    }

    @Test
    void aBuiltinModuleIsCheckedWhenItIsRegistered() {
        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class, () -> {
            BuiltinModules.register("gt.twice", BASE);
            BuiltinModules.register("gt.twice", BASE);
        });
        assertTrue(twice.getMessage().contains("登记了两次"), twice.getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("gt.broken_b",
                "-- Broken.\nlocal x = = 1")).getMessage().contains("gt.broken_b:2:"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("gt.silent",
                "local M = {}\n---One.\nfunction M.one() end\nreturn M")).getMessage().contains("注释"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("gt.bare",
                "-- Bare.\nlocal M = {}\nfunction M.one() end\nreturn M")).getMessage().contains("上面没写注释"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("gt.dash-x", BASE))
                .getMessage().contains("名字不行"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("string.x", BASE))
                .getMessage().contains("名字不行"));
    }
}
