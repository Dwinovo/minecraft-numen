package com.dwinovo.numen.api.script;

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
class HerModulesTest {

    private static final String BASE = "-- Test base.\nlocal M = {}\n---Say one.\nfunction M.one() return 1 end\nreturn M\n";
    private static final String NEWER = BASE.replace("return 1", "return 2");
    private static final String MINE = BASE.replace("Test base.", "Mine.");
    private static final UUID OWNER = UUID.randomUUID();

    @TempDir
    Path dir;

    /** 服务端的内置模块:名字到正文,用例拨。 */
    private final java.util.Map<String, String> shipped = new java.util.TreeMap<>();

    private HerModules modules() {
        return new HerModules(new com.dwinovo.numen.api.home.Home(dir, com.dwinovo.numen.api.NumenIo.inline("test")),
                new HerModules.Builtins() {
                    @Override
                    public java.util.Set<String> names() {
                        return shipped.keySet();
                    }

                    @Override
                    public String code(String name) {
                        return shipped.get(name);
                    }
                });
    }

    /** 她此刻看到的这一份的正文:她的,没有就是内置的没关掉的。 */
    private static String codeOf(HerModules modules, String name) {
        Modules.Module module = modules.get(name);
        return module == null ? null : module.code();
    }

    /** 服务端那一头多了一份内置。 */
    private void builtin(String name, String code) {
        shipped.put(name, code);
    }

    private Path mine(String name) {
        int dot = name.indexOf('.');
        return dir.resolve("modules").resolve(name.substring(0, dot)).resolve(name.substring(dot + 1) + ".lua");
    }

    @Test
    void aBuiltinModuleIsKnownByNameAndIsNotHers() {
        HerModules modules = modules();
        builtin("gt.layer", BASE);

        assertEquals(BASE, codeOf(modules, "gt.layer"));
        assertEquals(Modules.Origin.FACTORY, modules.get("gt.layer").origin());
        assertTrue(modules.names().contains("gt.layer"));
        assertFalse(Files.exists(mine("gt.layer")), "内置的不复制到她的目录里");
    }

    @Test
    void savingUnderABuiltinNameChangesItForHerAndLeavesTheServerDirectoryAlone() throws IOException {
        HerModules modules = modules();
        builtin("gt.layer", BASE);

        assertEquals(HerModules.Saved.CHANGED, modules.save("gt.layer", MINE));

        assertEquals(MINE, codeOf(modules, "gt.layer"), "程序用的是她改过的");
        assertEquals(Modules.Origin.CHANGED, modules.get("gt.layer").origin());
        assertFalse(modules.get("gt.layer").newer(), "内置那份没变");
        assertEquals(MINE, Files.readString(mine("gt.layer")), "写进主人的目录");
        assertEquals(BASE, shipped.get("gt.layer"), "服务端的原样");
    }

    @Test
    void resetDropsHerCopyAndTheBuiltinShowsThroughAgain() {
        HerModules modules = modules();
        builtin("gt.layer", BASE);
        modules.save("gt.layer", MINE);

        assertEquals(BASE, modules.reset("gt.layer"));

        assertEquals(Modules.Origin.FACTORY, modules.get("gt.layer").origin());
        assertEquals(BASE, codeOf(modules, "gt.layer"));
        assertFalse(Files.exists(mine("gt.layer")));
        assertNull(modules.reset("gt.nothing"), "没有内置的那一份没东西可还原");
    }

    @Test
    void aBuiltinThatChangedAfterSheChangedHersIsMarkedNewer() {
        HerModules modules = modules();
        builtin("gt.layer", BASE);
        modules.save("gt.layer", MINE);

        builtin("gt.layer", NEWER);

        assertTrue(modules.get("gt.layer").newer(), "内置有新版,她这份是改过的,没跟着换");
        modules.reset("gt.layer");
        assertEquals(NEWER, codeOf(modules, "gt.layer"));
        assertFalse(modules.get("gt.layer").newer());
    }

    @Test
    void deletingABuiltinSwitchesItOffForHerUntilReset() throws IOException {
        HerModules modules = modules();
        builtin("gt.gone", BASE);

        assertEquals(BASE, modules.delete("gt.gone"));

        assertNull(modules.get("gt.gone"));
        assertTrue(modules.deletedFactory().contains("gt.gone"));
        assertEquals(BASE, shipped.get("gt.gone"), "服务端的内置不动");
        assertEquals(BASE, modules.reset("gt.gone"));
        assertEquals(BASE, codeOf(modules, "gt.gone"), "reset 打开");
        assertFalse(modules.deletedFactory().contains("gt.gone"));
    }

    @Test
    void savingOverADeletedBuiltinNameBringsHerCopyBack() {
        HerModules modules = modules();
        builtin("gt.gone", BASE);
        modules.delete("gt.gone");

        modules.save("gt.gone", MINE);

        assertEquals(MINE, codeOf(modules, "gt.gone"));
    }

    @Test
    void herOwnModulesGoWhereTheirNameSaysAndAreReadAfterAReload() throws IOException {
        HerModules modules = modules();

        assertEquals(HerModules.Saved.NEW, modules.save("my.gt_mine", "-- One.\nreturn {}\n"));
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
        HerModules modules = modules();
        modules.save("my.gt_rec", "-- Rec.\nreturn {}\n");
        modules.tally("my.gt_rec", true, 0, null, 1000L);
        modules.tally("my.gt_rec", false, 3, "out of reach", 2000L);
        assertEquals(new HerModules.Stats(2, 1, 2000L, 3, "out of reach"), modules.stats("my.gt_rec"));
        assertEquals(HerModules.Saved.REPLACED, modules.save("my.gt_rec", "-- Rec again.\nreturn {}\n"));
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
        HerModules modules = modules();
        assertThrows(IllegalArgumentException.class, () -> modules.save("lumber", BASE));
        assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("my", BASE));
        Files.createDirectories(mine("my.x").getParent());
        Files.writeString(mine("my.x").getParent().getParent().resolve("stray.lua"), "return {}\n");
        modules.reload();
        assertNull(modules.get("stray"));
        assertFalse(modules.names().contains("stray"));
    }

    @Test
    void theRunViewIsTheBuiltinsOverlaidByHersMinusWhatSheSwitchedOff() {
        BuiltinModules.register("gt.view_a", BASE);
        BuiltinModules.register("gt.view_b", BASE);
        Modules view = Modules.of(OWNER, java.util.Map.of("gt.view_a", MINE, "my.view_c", MINE), java.util.Set.of("gt.view_b"));

        assertEquals(MINE, view.code("gt.view_a"), "her copy covers the built-in one");
        assertEquals(Modules.Origin.CHANGED, view.get("gt.view_a").origin());
        assertNull(view.get("gt.view_b"), "switched off for her");
        assertEquals(Modules.Origin.HERS, view.get("my.view_c").origin());
        assertEquals(Modules.Origin.FACTORY, Modules.factory().get("gt.view_a").origin());
    }

    @Test
    void aShippedModuleIsCheckedWhenItIsRegistered() {
        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class, () -> {
            BuiltinModules.register("gt.twice", BASE);
            BuiltinModules.register("gt.twice", BASE);
        });
        assertTrue(twice.getMessage().contains("is registered twice"), twice.getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("gt.broken_b",
                "-- Broken.\nlocal x = = 1")).getMessage().contains("gt.broken_b:2:"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("gt.dash-x", BASE))
                .getMessage().contains("has a bad name"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BuiltinModules.register("string.x", BASE))
                .getMessage().contains("has a bad name"));
    }
}
