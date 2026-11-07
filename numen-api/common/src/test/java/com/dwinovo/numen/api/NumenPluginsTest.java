package com.dwinovo.numen.api;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件那扇门:登记的种类就是类型表里和内置事件同一形状的一行,内置的行改不了;客户端那一侧只收主人的话和
 * 登记过的世界事件,没有主人客户端的地方如实说没送出去;某个插件的状态片段算炸了只丢它自己那段。
 */
class NumenPluginsTest {

    static {
        com.dwinovo.numen.api.plugin.PluginRosterFixture.install();
    }

    private static NumenApi door() {
        AtomicReference<NumenApi> api = new AtomicReference<>();
        NumenPlugins.register("gt", api::set);
        return api.get();
    }

    @Test
    void aRegisteredEventTypeIsARowShapedLikeTheBuiltInEvents() {
        NumenApi numen = door();
        numen.registerEventType("gametest_accessory_changed", false);
        numen.registerEventType("gametest_accessory_broke", true);

        for (String id : List.of("gametest_accessory_changed", "gametest_accessory_broke")) {
            assertTrue(EventTypes.isRegistered(id), id);
            EventTypes.Type row = EventTypes.get(id);
            EventTypes.Type builtIn = EventTypes.get(EventTypes.DIMENSION_CHANGE);
            assertEquals(builtIn.delivery(), row.delivery(), id + " 与内置事件同样插话投递");
            assertEquals(builtIn.clearedByInterrupt(), row.clearedByInterrupt(), id + " 按了停止也还在");
            assertEquals(builtIn.fromOwner(), row.fromOwner(), id + " 不是主人说的");
            assertNull(row.chatPreview().apply("x"), id + " 不进聊天流");
        }
        assertFalse(EventTypes.get("gametest_accessory_changed").alwaysUrgent());
        assertTrue(EventTypes.get("gametest_accessory_broke").alwaysUrgent());

        EventQueue q = new EventQueue(EventQueue.Journal.NONE);
        assertTrue(q.push("gametest_accessory_broke", "<event kind=\"gametest_accessory_broke\">碎了</event>", 0L, false),
                "登记成恒急的,发送方没标也是急件");
    }

    /** 一组绑定不了,登记那一刻就抛出,那句话说是哪个函数、哪一条:不记一行日志了事、让这一组悄悄缺席。 */
    @Test
    void aGroupThatCannotBindFailsTheRegistrationNamingIt() {
        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class, () ->
                NumenPlugins.register("gt", numen -> numen.api("gt_broken", "Test fixture.", Broken.class)));
        assertTrue(broken.getMessage().contains("gt.gt_broken.act"), broken.getMessage());
    }

    /** 绑定不了的一组:函数的第一个参数不是调用。 */
    public static final class Broken {

        @com.dwinovo.numen.api.sdk.Fn("Does nothing.")
        public static void act(String notACall) {
        }
    }

    @Test
    void withoutTheOwnersClientTheOwnersWordsAreNotDelivered() {
        assertEquals(Delivery.REJECTED, door().emit(UUID.randomUUID(), EventTypes.QUERY, "在吗"),
                "专用服务器上没有主人的客户端");
    }

    @Test
    void aPluginCannotTakeOverABuiltInType() {
        assertThrows(IllegalArgumentException.class, () -> door().registerEventType(EventTypes.DEATH, false),
                "死亡恒为急件是引擎的语义,插件改不了");
        assertTrue(EventTypes.get(EventTypes.DEATH).alwaysUrgent());
    }

    @Test
    void theClientDoorTakesTheOwnersWordsAndRegisteredWorldEventsOnly() {
        NumenApi numen = door();
        numen.registerEventType("gametest_stream_comment", false);
        UUID companion = UUID.randomUUID();

        assertEquals(Delivery.REJECTED, numen.emit(companion, "gametest_stream_comment", "弹幕:唱首歌"),
                "登记过的世界事件收;这里没有主人客户端,如实说没送出去");
        assertThrows(IllegalArgumentException.class, () -> numen.emit(companion, "谁也没登记过", "x"),
                "没登记的种类不管在哪一侧都拒");
        for (String control : List.of(EventTypes.GOAL, EventTypes.COMPACT, EventTypes.CLEAR)) {
            assertThrows(IllegalArgumentException.class, () -> numen.emit(companion, control, "x"),
                    control + " 是引擎自己的输入,不从门外进");
        }
    }

    @Test
    void aFailingFragmentIsSkippedUntilItComputesAgain() {
        UUID self = UUID.randomUUID();
        boolean[] broken = {true};
        java.util.function.Function<UUID, String> flaky = id -> {
            if (!id.equals(self)) return "";
            if (broken[0]) throw new IllegalStateException("gametest: 读不到");
            return "<gametest_broken>ok</gametest_broken>";
        };
        java.util.function.Function<UUID, String> fine = id -> id.equals(self) ? "<gametest_fine>ok</gametest_fine>" : "";
        List<java.util.function.Function<UUID, String>> fragments = List.of(flaky, fine);

        for (int tick = 0; tick < 3; tick++) {
            assertEquals("<gametest_fine>ok</gametest_fine>", NumenPlugins.joinFragments(fragments, self),
                    "算炸的那段丢掉,后面的照常挂上");
        }
        broken[0] = false;
        assertEquals("<gametest_broken>ok</gametest_broken><gametest_fine>ok</gametest_fine>",
                NumenPlugins.joinFragments(fragments, self), "恢复以后那段回来");
    }

    /**
     * 停用名单上关了 Numen:它的内容不装,引擎(宿主)自己的登记照常,花名册里也没有引擎的一行。
     * 单测的类路径上本来就只有引擎,没有 Numen 的内容,引擎在那里一直能起来。
     */
    @Test
    void theEngineStaysUpWhenNumenIsOnTheStopList(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        var switches = new com.dwinovo.numen.api.plugin.PluginSwitches(new com.dwinovo.numen.api.store.JsonDoc(
                dir.resolve("plugins.json"), com.dwinovo.numen.api.store.JsonDoc.Format.of(1),
                com.dwinovo.numen.api.store.Io.direct(e -> {
                    throw e;
                })));
        switches.write(java.util.Set.of(NumenPlugins.NUMEN));
        var before = NumenPlugins.roster();
        var roster = new com.dwinovo.numen.api.plugin.PluginRoster(() -> switches,
                id -> com.dwinovo.numen.api.plugin.PluginRoster.Meta.NONE);
        NumenPlugins.useRoster(roster);
        try {
            NumenPlugins.registerHost(numen -> numen.registerEventType("gametest_engine_up", false));
            NumenPlugins.register(NumenPlugins.NUMEN, numen -> numen.registerEventType("gametest_numen_up", false));

            assertTrue(EventTypes.isRegistered("gametest_engine_up"));
            assertFalse(EventTypes.isRegistered("gametest_numen_up"));
            assertEquals(List.of(NumenPlugins.NUMEN), roster.plugins().stream().map(p -> p.id()).toList(),
                    "名单上的 Numen 留着一行(已关闭),引擎没有行");
            assertEquals(com.dwinovo.numen.api.plugin.PluginRoster.Status.DISABLED, roster.plugins().get(0).status());
        } finally {
            NumenPlugins.useRoster(before);
        }
    }
}
