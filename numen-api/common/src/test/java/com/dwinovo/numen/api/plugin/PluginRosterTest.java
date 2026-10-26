package com.dwinovo.numen.api.plugin;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.plugin.PluginRoster.Kind;
import com.dwinovo.numen.api.plugin.PluginRoster.Meta;
import com.dwinovo.numen.api.plugin.PluginRoster.Source;
import com.dwinovo.numen.api.plugin.PluginRoster.Status;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件花名册:每个插件留一行(联动的目标没装也在),停用名单在启动时读一次决定装不装、之后改的只管下次启动,
 * 装的时候记下它加了什么,装不上的原因照实记。
 */
class PluginRosterTest {

    /** 记什么都不做的门:只验记账,登记本身不是这里的事。 */
    private static final NumenApi DOOR = (NumenApi) Proxy.newProxyInstance(PluginRosterTest.class.getClassLoader(),
            new Class<?>[] {NumenApi.class}, (proxy, method, args) -> null);

    private static PluginSwitches switches(Path dir) {
        return new PluginSwitches(new JsonDoc(dir.resolve("plugins.json"), JsonDoc.Format.of(1), Io.direct(e -> {
            throw e;
        })));
    }

    private static PluginRoster roster(Path dir) {
        return new PluginRoster(() -> switches(dir), id -> Meta.NONE);
    }

    private static PluginRoster.Plugin row(PluginRoster roster, String id) {
        return roster.plugins().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aLinkIsListedWithItsTargetAndSource(@TempDir Path dir) {
        PluginRoster roster = roster(dir);

        assertTrue(roster.admit(Source.BUNDLED, "ysm", "yes_steve_model"));

        PluginRoster.Plugin link = row(roster, "ysm");
        assertEquals("yes_steve_model", link.target());
        assertEquals(Source.BUNDLED, link.source());
        assertEquals(Status.LOADED, link.status());
    }

    /** 名单上写着一个这台服务器没装目标的联动:那一行留在文件里不动,装回目标模组后照样生效。 */
    @Test
    void anEntryForALinkWhoseTargetIsAbsentStaysInTheFile(@TempDir Path dir) {
        switches(dir).write(java.util.Set.of("tlm"));
        PluginRoster roster = roster(dir);
        roster.run("other", numen -> { }, DOOR);
        assertNull(roster.setDisabled("other", true));

        assertTrue(roster.plugins().stream().noneMatch(p -> p.id().equals("tlm")));
        assertEquals(java.util.Set.of("tlm", "other"), switches(dir).disabled());
    }

    @Test
    void aPluginOnTheListAtStartIsNotSetUpAndNothingChangesThisRun(@TempDir Path dir) {
        switches(dir).write(java.util.Set.of("off"));
        PluginRoster roster = roster(dir);
        AtomicInteger ran = new AtomicInteger();
        NumenPlugin plugin = numen -> ran.incrementAndGet();

        roster.run("off", plugin, DOOR);
        roster.run("on", plugin, DOOR);

        assertEquals(1, ran.get(), "名单上的 setup 不跑");
        assertEquals(Status.DISABLED, row(roster, "off").status());
        assertEquals(Status.LOADED, row(roster, "on").status());
        assertTrue(row(roster, "off").disabledNext());
        assertFalse(row(roster, "off").pending(), "下次启动的设定和这一次一样");

        assertNull(roster.setDisabled("off", false));
        assertNull(roster.setDisabled("on", true));

        assertEquals(Status.DISABLED, row(roster, "off").status(), "这一次的运行不变");
        assertEquals(Status.LOADED, row(roster, "on").status());
        assertTrue(row(roster, "off").pending());
        assertTrue(row(roster, "on").pending());
        assertEquals(java.util.Set.of("on"), switches(dir).disabled(), "改动落了盘");
    }

    @Test
    void theNextStartReadsWhatWasWritten(@TempDir Path dir) {
        PluginRoster first = roster(dir);
        first.admit(Source.JAR, "probe", "");
        assertNull(first.setDisabled("probe", true));

        PluginRoster second = roster(dir);
        AtomicInteger ran = new AtomicInteger();
        second.run("probe", numen -> ran.incrementAndGet(), DOOR);

        assertEquals(0, ran.get());
        assertEquals(Status.DISABLED, row(second, "probe").status());
    }

    /** Numen 的内容也是插件之一:在启动名单上就不装,和别的插件一样改下次启动的设定。 */
    @Test
    void numenIsAPluginLikeAnyOtherAndCanBeSwitchedOff(@TempDir Path dir) {
        switches(dir).write(java.util.Set.of(NumenPlugins.NUMEN));
        PluginRoster roster = roster(dir);

        assertFalse(roster.admit(Source.BUNDLED, NumenPlugins.NUMEN, ""));

        assertEquals(Status.DISABLED, row(roster, NumenPlugins.NUMEN).status());
        assertNull(roster.setDisabled(NumenPlugins.NUMEN, false));
        assertTrue(row(roster, NumenPlugins.NUMEN).pending());
        assertNotNull(roster.setDisabled("nobody", true), "没登记过的插件没有开关");
    }

    @Test
    void aSetupThatThrowsIsRecordedWithItsReasonAndRethrown(@TempDir Path dir) {
        PluginRoster roster = roster(dir);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                roster.run("broken", numen -> {
                    throw new IllegalStateException("没有 Foo");
                }, DOOR));

        assertEquals("没有 Foo", thrown.getMessage());
        PluginRoster.Plugin row = row(roster, "broken");
        assertEquals(Status.FAILED, row.status());
        assertTrue(row.reason().contains("没有 Foo"), row.reason());
    }

    @Test
    void whatAPluginAddsIsCountedFromWhatItRegistered(@TempDir Path dir) throws IOException {
        Path numenDir = dir.resolve("numen");
        Files.createDirectories(numenDir.resolve("skills").resolve("p").resolve("a"));
        Files.createDirectories(numenDir.resolve("skills").resolve("p").resolve("b"));
        Files.writeString(numenDir.resolve("skills").resolve("p").resolve("a").resolve("SKILL.md"), "x");
        Files.writeString(numenDir.resolve("skills").resolve("p").resolve("b").resolve("SKILL.md"), "x");
        Files.createDirectories(numenDir.resolve("modules").resolve("p"));
        Files.writeString(numenDir.resolve("modules").resolve("p").resolve("g.lua"), "return {}");
        PluginRoster roster = roster(dir);

        roster.run("p", numen -> {
            numen.on(CompanionEvent.SPAWN, body -> { });
            numen.on(CompanionEvent.REMOVE, body -> { });
            numen.api("one", "x", Object.class);
            numen.api("two", "x", Object.class);
            numen.command(LiteralArgumentBuilder.literal("verb"));
            numen.registerEventType("p_event", false);
            numen.bundleContent(numenDir);
        }, DOOR);

        assertEquals(Map.of(Kind.EVENT_LISTENER, 2, Kind.API_GROUP, 2, Kind.COMMAND, 1, Kind.EVENT_TYPE, 1,
                Kind.SKILL, 2, Kind.MODULE, 1), row(roster, "p").adds());
    }

    @Test
    void oneIdRegisteredSeveralTimesIsOneRowThatAddsUp(@TempDir Path dir) {
        PluginRoster roster = roster(dir);

        roster.run("same", numen -> numen.api("a", "x", Object.class), DOOR);
        roster.run("same", numen -> numen.api("b", "x", Object.class), DOOR);

        assertEquals(1, roster.plugins().size());
        assertEquals(2, row(roster, "same").adds().get(Kind.API_GROUP));
    }

    @Test
    void anEmbeddedLinkIsNamedAfterItsTargetAndCarriesNumensVersion(@TempDir Path dir) {
        Map<String, Meta> mods = Map.of(
                NumenPlugins.NUMEN, new Meta("Numen", "1.2.3", List.of("dwinovo")),
                "touhou_little_maid", new Meta("Touhou Little Maid", "9", List.of("TartaricAcid")),
                "standalone", new Meta("Stand Alone", "0.5", List.of("someone")));
        PluginRoster roster = new PluginRoster(() -> switches(dir), id -> mods.getOrDefault(id, Meta.NONE));

        roster.admit(Source.BUNDLED, "tlm", "touhou_little_maid");
        roster.run("standalone", numen -> { }, DOOR);
        roster.run("anonymous", numen -> { }, DOOR);

        PluginRoster.Plugin link = row(roster, "tlm");
        assertEquals("Touhou Little Maid", link.name());
        assertEquals("1.2.3", link.version());
        assertEquals(List.of("dwinovo"), link.authors());
        PluginRoster.Plugin jar = row(roster, "standalone");
        assertEquals("Stand Alone", jar.name());
        assertEquals("0.5", jar.version());
        assertEquals("anonymous", row(roster, "anonymous").name(), "加载器不认识它,就用 id");
    }
}
