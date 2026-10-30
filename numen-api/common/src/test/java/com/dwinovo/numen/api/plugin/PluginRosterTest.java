package com.dwinovo.numen.api.plugin;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.agent.scope.Scope;
import com.dwinovo.numen.api.plugin.PluginRoster.Kind;
import com.dwinovo.numen.api.plugin.PluginRoster.Meta;
import com.dwinovo.numen.api.plugin.PluginRoster.Source;
import com.dwinovo.numen.api.plugin.PluginRoster.Status;
import com.dwinovo.numen.api.plugin.PluginRoster.Unloading;
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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件花名册:每个插件留一行(联动的目标没装也在),停用名单决定装不装、开关当场装上或撤回它的登记(声明了卸下要重启的除外),
 * 装的时候记下它加了什么,装不上的原因照实记。
 */
class PluginRosterTest {

    private static final Function<Scope, NumenApi> DOOR = PluginRosterFixture.INERT_DOORS;

    /**
     * 只往作用域里记一笔的门:一次 {@code on} 是一条登记,撤回时把计数减回去——验花名册的开关与撤回,登记表本身另有各自的测试。
     */
    private static Function<Scope, NumenApi> counting(AtomicInteger registered) {
        return scope -> (NumenApi) Proxy.newProxyInstance(PluginRosterTest.class.getClassLoader(),
                new Class<?>[] {NumenApi.class}, (proxy, method, args) -> {
                    if (method.getName().equals("on")) {
                        registered.incrementAndGet();
                        scope.add(registered::decrementAndGet);
                    }
                    return null;
                });
    }

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
        roster.run("other", numen -> { }, Unloading.LIVE, DOOR);
        assertNull(roster.setDisabled("other", true));

        assertTrue(roster.plugins().stream().noneMatch(p -> p.id().equals("tlm")));
        assertEquals(java.util.Set.of("tlm", "other"), switches(dir).disabled());
    }

    @Test
    void aPluginOnTheListAtStartIsNotSetUpUntilItIsSwitchedOn(@TempDir Path dir) {
        switches(dir).write(java.util.Set.of("off"));
        PluginRoster roster = roster(dir);
        AtomicInteger registered = new AtomicInteger();
        AtomicInteger ran = new AtomicInteger();
        NumenPlugin plugin = numen -> {
            ran.incrementAndGet();
            numen.on(CompanionEvent.SPAWN, body -> { });
        };

        roster.run("off", plugin, Unloading.LIVE, counting(registered));
        roster.run("on", plugin, Unloading.LIVE, counting(registered));

        assertEquals(1, ran.get(), "名单上的 setup 不跑");
        assertEquals(1, registered.get());
        assertEquals(Status.DISABLED, row(roster, "off").status());
        assertEquals(Status.LOADED, row(roster, "on").status());
        assertTrue(row(roster, "off").disabled());
        assertFalse(row(roster, "off").pending(), "设定和状态一致");

        assertNull(roster.setDisabled("off", false));
        assertEquals(2, ran.get(), "开关一翻,登记当场装上");
        assertEquals(Status.LOADED, row(roster, "off").status());
        assertEquals(2, registered.get());

        assertNull(roster.setDisabled("on", true));
        assertEquals(1, registered.get(), "关掉的那个插件登记的一切当场撤回");
        assertEquals(Status.DISABLED, row(roster, "on").status());
        assertEquals(Map.of(), row(roster, "on").adds());
        assertFalse(row(roster, "on").pending());
        assertEquals(java.util.Set.of("on"), switches(dir).disabled(), "改动落了盘");
    }

    /** 关了再开:setup 再跑一遍,登记回来,记数从头记。 */
    @Test
    void switchingOffAndOnAgainRegistersItAgain(@TempDir Path dir) {
        PluginRoster roster = roster(dir);
        AtomicInteger registered = new AtomicInteger();
        roster.run("p", numen -> numen.on(CompanionEvent.SPAWN, body -> { }), Unloading.LIVE, counting(registered));

        assertNull(roster.setDisabled("p", true));
        assertEquals(0, registered.get());
        assertNull(roster.setDisabled("p", false));
        assertEquals(1, registered.get());
        assertEquals(Map.of(Kind.EVENT_LISTENER, 1), row(roster, "p").adds());
    }

    /** 声明了卸下要重启的插件:关它登记照旧留着,界面标重启后生效;翻回来就对得上了。 */
    @Test
    void aPluginThatNeedsARestartToUnloadStaysUntilThen(@TempDir Path dir) {
        PluginRoster roster = roster(dir);
        AtomicInteger registered = new AtomicInteger();
        roster.run("loader", numen -> numen.on(CompanionEvent.SPAWN, body -> { }), Unloading.NEEDS_RESTART,
                counting(registered));

        assertNull(roster.setDisabled("loader", true));
        assertEquals(1, registered.get());
        assertEquals(Status.LOADED, row(roster, "loader").status());
        assertTrue(row(roster, "loader").disabled());
        assertTrue(row(roster, "loader").pending());
        assertThrows(IllegalStateException.class, () -> roster.withdraw("loader"));
        assertThrows(IllegalStateException.class, () -> roster.reload("loader"));

        assertNull(roster.setDisabled("loader", false));
        assertFalse(row(roster, "loader").pending());
    }

    /** 启动时在名单上、登记方没把 setup 交来(只报了到):翻到开装不上,标重启后生效。 */
    @Test
    void aPluginThatWasNeverSetUpNeedsARestartToSwitchOn(@TempDir Path dir) {
        switches(dir).write(java.util.Set.of("late"));
        PluginRoster roster = roster(dir);
        assertFalse(roster.admit(Source.BUNDLED, "late", "some_mod"));

        assertNull(roster.setDisabled("late", false));

        assertEquals(Status.DISABLED, row(roster, "late").status());
        assertTrue(row(roster, "late").pending());
    }

    /** 重登 = 撤回 + setup 再跑;撤回 = 登记与花名册的行都没了;两样都发"登记变了"。 */
    @Test
    void reloadRunsTheSetupAgainAndWithdrawRemovesTheRow(@TempDir Path dir) {
        PluginRoster roster = roster(dir);
        AtomicInteger registered = new AtomicInteger();
        AtomicInteger changed = new AtomicInteger();
        var listening = Registrations.onChanged(changed::incrementAndGet);
        try {
            roster.run("p", numen -> numen.on(CompanionEvent.SPAWN, body -> { }), Unloading.LIVE, counting(registered));
            int afterRun = changed.get();

            roster.reload("p");
            assertEquals(1, registered.get(), "撤了旧的、装了新的,不是叠上去");
            assertTrue(changed.get() > afterRun, "重登发了登记变了");

            int afterReload = changed.get();
            roster.withdraw("p");
            assertEquals(0, registered.get());
            assertTrue(changed.get() > afterReload);
            assertTrue(roster.plugins().stream().noneMatch(p -> p.id().equals("p")));
            assertThrows(IllegalArgumentException.class, () -> roster.withdraw("p"));
        } finally {
            listening.close();
        }
    }

    /** 装上时 setup 抛了:这个插件登记了一半的也撤回,不留半截;开关翻了、状态是失败、原因交回。 */
    @Test
    void aSetupThatThrowsWhenSwitchedOnLeavesNothingBehind(@TempDir Path dir) {
        switches(dir).write(java.util.Set.of("shaky"));
        PluginRoster roster = roster(dir);
        AtomicInteger registered = new AtomicInteger();
        roster.run("shaky", numen -> {
            numen.on(CompanionEvent.SPAWN, body -> { });
            throw new IllegalStateException("没有 Foo");
        }, Unloading.LIVE, counting(registered));

        String problem = roster.setDisabled("shaky", false);

        assertTrue(problem.contains("没有 Foo"), problem);
        assertEquals(0, registered.get());
        assertEquals(Status.FAILED, row(roster, "shaky").status());
    }

    @Test
    void theNextStartReadsWhatWasWritten(@TempDir Path dir) {
        PluginRoster first = roster(dir);
        first.admit(Source.JAR, "probe", "");
        assertNull(first.setDisabled("probe", true));

        PluginRoster second = roster(dir);
        AtomicInteger ran = new AtomicInteger();
        second.run("probe", numen -> ran.incrementAndGet(), Unloading.LIVE, DOOR);

        assertEquals(0, ran.get());
        assertEquals(Status.DISABLED, row(second, "probe").status());
    }

    /** Numen 的内容也是插件之一:在启动名单上就不装,和别的插件一样用开关管。 */
    @Test
    void numenIsAPluginLikeAnyOtherAndCanBeSwitchedOff(@TempDir Path dir) {
        switches(dir).write(java.util.Set.of(NumenPlugins.NUMEN));
        PluginRoster roster = roster(dir);

        assertFalse(roster.admit(Source.BUNDLED, NumenPlugins.NUMEN, ""));

        assertEquals(Status.DISABLED, row(roster, NumenPlugins.NUMEN).status());
        assertNull(roster.setDisabled(NumenPlugins.NUMEN, false));
        assertTrue(row(roster, NumenPlugins.NUMEN).pending(), "它的内容没装上过,开起来要重启");
        assertNotNull(roster.setDisabled("nobody", true), "没登记过的插件没有开关");
    }

    @Test
    void aSetupThatThrowsIsRecordedWithItsReasonAndRethrown(@TempDir Path dir) {
        PluginRoster roster = roster(dir);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                roster.run("broken", numen -> {
                    throw new IllegalStateException("没有 Foo");
                }, Unloading.LIVE, DOOR));

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
        }, Unloading.LIVE, DOOR);

        assertEquals(Map.of(Kind.EVENT_LISTENER, 2, Kind.API_GROUP, 2, Kind.COMMAND, 1, Kind.EVENT_TYPE, 1,
                Kind.SKILL, 2, Kind.MODULE, 1), row(roster, "p").adds());
    }

    @Test
    void oneIdRegisteredSeveralTimesIsOneRowThatAddsUp(@TempDir Path dir) {
        PluginRoster roster = roster(dir);

        roster.run("same", numen -> numen.api("a", "x", Object.class), Unloading.LIVE, DOOR);
        roster.run("same", numen -> numen.api("b", "x", Object.class), Unloading.LIVE, DOOR);

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
        roster.run("standalone", numen -> { }, Unloading.LIVE, DOOR);
        roster.run("anonymous", numen -> { }, Unloading.LIVE, DOOR);

        PluginRoster.Plugin link = row(roster, "tlm");
        assertEquals("Touhou Little Maid", link.name());
        assertEquals("1.2.3", link.version());
        assertEquals(List.of("dwinovo"), link.authors());
        PluginRoster.Plugin jar = row(roster, "standalone");
        assertEquals("Stand Alone", jar.name());
        assertEquals("0.5", jar.version());
        assertEquals("anonymous", row(roster, "anonymous").name(), "加载器不认识它,就用 id");
    }

    /** 一个来源的 id 不能被另一种来源往里加东西:脚本不能给 tlm 添登记。 */
    @Test
    void aScriptCannotAddToAPluginOfAnotherSource(@TempDir Path dir) {
        PluginRoster roster = roster(dir);
        roster.run("tlm", numen -> { }, Unloading.LIVE, DOOR);

        assertThrows(IllegalArgumentException.class, () -> roster.admit(Source.SCRIPT, "tlm", ""));
    }
}
