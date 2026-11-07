package com.dwinovo.numen.api;

import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.entity.CompanionEvents;
import com.dwinovo.numen.api.library.Shipped;
import com.dwinovo.numen.api.plugin.PluginRoster.Status;
import com.dwinovo.numen.api.plugin.PluginRoster.Unloading;
import com.dwinovo.numen.api.script.BuiltinModules;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import com.dwinovo.numen.api.sdk.Codec;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.api.sdk.SdkFixture;
import com.dwinovo.numen.script.ScriptType;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一个插件的门登记进各张表的东西都归在它的作用域下:撤回(关开关、重载)把每张表里它那一条撤干净,重登把 setup 再跑一遍,
 * 撤回之后它原来那扇门不再登记任何东西。表本身:函数组、事件类型、同伴事件订阅、值转换、穿戴来源、随附的 Lua 模块与内容目录。
 */
class NumenPluginScopeTest {

    static {
        SdkFixture.ready();
    }

    /** 一个测试自己的类型,只用来验值转换的登记与撤回。 */
    public record Marker(String label) {}

    private static final Codec<Marker> MARKER = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.STRING;
        }

        @Override
        public Marker decode(Object value) {
            return new Marker(String.valueOf(value));
        }

        @Override
        public Object encode(Marker value) {
            return value.label();
        }
    };

    /** 一组测试函数。 */
    public static final class Scoped {

        private Scoped() {}

        @Fn("Do nothing.")
        public static void act(com.dwinovo.numen.api.sdk.ServerCall call) {
        }
    }

    private static boolean codecKnown() {
        try {
            LuaCodecs.encode(new Marker("x"));
            return true;
        } catch (IllegalStateException unknown) {
            return false;
        }
    }

    private static com.dwinovo.numen.api.plugin.PluginRoster.Plugin row(String id) {
        return NumenPlugins.roster().plugins().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void switchingAPluginOffTakesEveryRegistrationOutAndOnPutsThemBack(@TempDir Path dir) throws IOException {
        Path modules = dir.resolve("modules").resolve("gt_scope");
        Files.createDirectories(modules);
        Files.writeString(modules.resolve("probe.lua"), "return {}");
        AtomicInteger heard = new AtomicInteger();
        AtomicInteger setups = new AtomicInteger();
        com.dwinovo.numen.api.gear.GearSource gear = new com.dwinovo.numen.api.gear.GearSource() {
            @Override
            public List<com.dwinovo.numen.api.gear.GearSlot> slots(com.dwinovo.numen.api.entity.NumenPlayer body) {
                return List.of();
            }

            @Override
            public Set<String> kindsOf(com.dwinovo.numen.api.entity.NumenPlayer body, ItemStack stack) {
                return Set.of("gt_scope_kind");
            }
        };

        NumenPlugins.register("gt_scope", numen -> {
            setups.incrementAndGet();
            numen.api("scoped", "Test fixture.", Scoped.class);
            numen.registerEventType("gt_scope_event", false);
            numen.on(CompanionEvent.ABORT, id -> heard.incrementAndGet());
            numen.codec(Marker.class, MARKER);
            numen.registerGear(gear);
            numen.bundleContent(dir);
        });

        assertNotNull(ApiRegistry.function("gt_scope.scoped.act"));
        assertTrue(EventTypes.isRegistered("gt_scope_event"));
        assertTrue(codecKnown());
        assertNotNull(BuiltinModules.get("gt_scope.probe"));
        assertTrue(Shipped.bundles().stream().anyMatch(b -> b.source().equals("gt_scope")));
        assertEquals(Set.of("gt_scope_kind"), NumenPlugins.gearKinds(null, (ItemStack) null));
        CompanionEvents.fire(CompanionEvent.ABORT, UUID.randomUUID());
        assertEquals(1, heard.get());

        assertNull(NumenPlugins.roster().setDisabled("gt_scope", true));

        assertNull(ApiRegistry.function("gt_scope.scoped.act"));
        assertNull(ApiRegistry.group("gt_scope.scoped"));
        assertFalse(EventTypes.isRegistered("gt_scope_event"));
        assertFalse(codecKnown());
        assertNull(BuiltinModules.get("gt_scope.probe"));
        assertTrue(Shipped.bundles().stream().noneMatch(b -> b.source().equals("gt_scope")));
        assertEquals(Set.of(), NumenPlugins.gearKinds(null, (ItemStack) null));
        CompanionEvents.fire(CompanionEvent.ABORT, UUID.randomUUID());
        assertEquals(1, heard.get(), "a withdrawn plugin hears nothing");
        assertEquals(Status.DISABLED, row("gt_scope").status());

        assertNull(NumenPlugins.roster().setDisabled("gt_scope", false));

        assertEquals(2, setups.get(), "re-registering runs the setup again");
        assertNotNull(ApiRegistry.function("gt_scope.scoped.act"));
        assertTrue(EventTypes.isRegistered("gt_scope_event"));
        assertTrue(codecKnown());
        assertNotNull(BuiltinModules.get("gt_scope.probe"));
        assertEquals(Set.of("gt_scope_kind"), NumenPlugins.gearKinds(null, (ItemStack) null));
        CompanionEvents.fire(CompanionEvent.ABORT, UUID.randomUUID());
        assertEquals(2, heard.get());
        assertEquals(Status.LOADED, row("gt_scope").status());

        NumenPlugins.roster().withdraw("gt_scope");
        assertNull(ApiRegistry.function("gt_scope.scoped.act"));
    }

    /** 撤回之后还拿着旧的门:点名是哪个插件,什么也不登记。 */
    @Test
    void aDoorFromBeforeTheWithdrawalRegistersNothing() {
        AtomicReference<NumenApi> door = new AtomicReference<>();
        NumenPlugins.register("gt_stale", door::set);
        NumenApi old = door.get();
        old.registerEventType("gt_stale_event", false);
        assertTrue(EventTypes.isRegistered("gt_stale_event"));

        NumenPlugins.roster().reload("gt_stale");

        assertFalse(EventTypes.isRegistered("gt_stale_event"), "the reload took the door's own registrations too");
        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> old.registerEventType("gt_stale_event", false));
        assertTrue(stale.getMessage().contains("gt_stale"), stale.getMessage());
        assertThrows(IllegalStateException.class, () -> old.api("late", "x", Scoped.class));
        assertThrows(IllegalStateException.class, () -> old.on(CompanionEvent.ABORT, id -> { }));
        assertNull(ApiRegistry.group("gt_stale.late"));
        assertFalse(EventTypes.isRegistered("gt_stale_event"));

        NumenApi fresh = door.get();
        assertTrue(fresh != old, "the setup of the reload got a new door");
        fresh.registerEventType("gt_stale_event", false);
        assertTrue(EventTypes.isRegistered("gt_stale_event"));
    }

    /** 大脑事件走同一个门:按登记顺序送、一个订阅者抛了其余照收、撤回之后收不到。 */
    @Test
    void brainEventsAreDeliveredInOrderIsolatedAndWithdrawnWithThePlugin() {
        List<String> heard = new java.util.ArrayList<>();
        UUID id = UUID.randomUUID();
        NumenPlugins.register("gt_brain_a", numen -> {
            numen.on(CompanionEvent.RUN_ENDED, ended -> heard.add("a:" + ended.outcome()));
            numen.on(CompanionEvent.RUN_STARTED, started -> {
                throw new IllegalStateException("订阅者写坏了");
            });
        });
        NumenPlugins.register("gt_brain_b", numen -> {
            numen.on(CompanionEvent.RUN_STARTED, started -> heard.add("b:started " + started.runId()));
            numen.on(CompanionEvent.RUN_ENDED, ended -> heard.add("b:" + ended.reason()));
        });

        CompanionEvents.fire(CompanionEvent.RUN_STARTED, new CompanionEvent.RunStarted(id, 7));
        CompanionEvents.fire(CompanionEvent.RUN_ENDED,
                new CompanionEvent.RunEnded(id, 7, CompanionEvent.Outcome.HALTED, "halt.owner_stop"));

        assertEquals(List.of("b:started 7", "a:HALTED", "b:halt.owner_stop"), heard);

        assertNull(NumenPlugins.roster().setDisabled("gt_brain_a", true));
        heard.clear();
        CompanionEvents.fire(CompanionEvent.RUN_ENDED,
                new CompanionEvent.RunEnded(id, 8, CompanionEvent.Outcome.DONE, ""));

        assertEquals(List.of("b:"), heard, "the withdrawn plugin hears nothing, the other still does");
        NumenPlugins.roster().withdraw("gt_brain_a");
        NumenPlugins.roster().withdraw("gt_brain_b");
    }

    /** 声明了卸下要重启的插件:关它,登记留着。 */
    @Test
    void aPluginThatNeedsARestartKeepsItsRegistrationsWhenSwitchedOff() {
        NumenPlugins.register("gt_pinned", Unloading.NEEDS_RESTART,
                numen -> numen.api("pinned", "Test fixture.", Scoped.class));

        assertNull(NumenPlugins.roster().setDisabled("gt_pinned", true));

        assertNotNull(ApiRegistry.function("gt_pinned.pinned.act"));
        assertTrue(row("gt_pinned").pending());
        assertNull(NumenPlugins.roster().setDisabled("gt_pinned", false));
    }
}
