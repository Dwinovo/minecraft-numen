package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.FakeWorld;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Verdict;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 面板的规则页读写的那一种库:整表和命令列规则读的是同一份({@link RuleBook}),加、删、清空、设模式都落在主人的存档上,
 * 写错了的规则带着教学式的错误被拒、不进表。
 */
@Tag("mc")
class PermissionLibraryTest {

    private static boolean booted;

    private final PermissionStore store = new PermissionStore();
    private final UUID blue = UUID.randomUUID();
    private final Map<UUID, String> companions = new LinkedHashMap<>(Map.of(blue, "小蓝"));
    private boolean hardRules = true;

    private final PermissionLibrary.Ground ground = new PermissionLibrary.Ground() {
        @Override
        public PermissionStore store() {
            return store;
        }

        @Override
        public boolean hardRules() {
            return hardRules;
        }

        @Override
        public Map<UUID, String> companions() {
            return companions;
        }
    };

    @BeforeAll
    static void boot() {
        booted = FakeWorld.boot();
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过权限库钉桩");
    }

    private static JsonObject body(String... keyValues) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            o.addProperty(keyValues[i], keyValues[i + 1]);
        }
        return o;
    }

    private JsonObject ruleRow(String layer, String table, String rule) {
        JsonArray rows = PermissionLibrary.view(ground);
        for (var e : rows) {
            JsonObject o = e.getAsJsonObject();
            if ("rule".equals(o.get("row").getAsString()) && layer.equals(o.get("layer").getAsString())
                    && table.equals(o.get("table").getAsString()) && rule.equals(o.get("rule").getAsString())) {
                return o;
            }
        }
        return null;
    }

    @Test
    void theTableListsAllThreeLayersWithTheCompanionsAndTheServersSwitch() {
        store.add(Verdict.Kind.DENY, Groups.parse("break(owner) @小蓝"));
        store.add(Verdict.Kind.ALLOW, new PermissionStore.Row(Groups.parse("attack(hostile)"),
                PermissionStore.Source.HER, "别误伤"));
        store.hit(Layers.OWNER, Verdict.Kind.DENY, Groups.parse("break(owner) @小蓝"));
        store.setMode(blue, Mode.OBSERVE);

        JsonArray rows = PermissionLibrary.view(ground);

        JsonObject settings = rows.get(0).getAsJsonObject();
        assertEquals("settings", settings.get("row").getAsString());
        assertTrue(settings.get("hardRules").getAsBoolean());
        JsonObject mode = rows.get(1).getAsJsonObject();
        assertEquals("mode", mode.get("row").getAsString());
        assertEquals(blue.toString(), mode.get("companion").getAsString());
        assertEquals("小蓝", mode.get("name").getAsString());
        assertEquals("observe", mode.get("mode").getAsString());

        assertNotNull(ruleRow("hard", "deny", "break(other)"), "硬规则");
        assertNotNull(ruleRow("factory", "ask", "break(owner)"), "出厂层");
        JsonObject mine = ruleRow("owner", "deny", "break(owner) @小蓝");
        assertEquals("owner", mine.get("source").getAsString());
        assertEquals(1, mine.get("count").getAsInt());
        assertTrue(mine.get("last").getAsLong() > 0);
        JsonObject hers = ruleRow("owner", "allow", "attack(hostile)");
        assertEquals("her", hers.get("source").getAsString());
        assertEquals("别误伤", hers.get("note").getAsString());
        assertEquals(0, hers.get("count").getAsInt());
    }

    @Test
    void whenTheServerOwnerSwitchesTheHardRulesOffTheyAreNotListed() {
        hardRules = false;

        JsonArray rows = PermissionLibrary.view(ground);

        assertFalse(rows.get(0).getAsJsonObject().get("hardRules").getAsBoolean());
        assertNull(ruleRow("hard", "deny", "break(other)"));
    }

    @Test
    void aRuleIsAddedOnceAndDeletedByItsText() {
        JsonObject row = body("table", "ask", "rule", "take(*)");

        assertNull(PermissionLibrary.write(ground, PermissionLibrary.PUT, row).problem());
        assertNull(PermissionLibrary.write(ground, PermissionLibrary.PUT, row).problem(), "再加一次已经如愿");
        assertEquals(1, store.rows(Verdict.Kind.ASK).size());
        assertNotNull(ruleRow("owner", "ask", "take(*)"));

        assertNotNull(PermissionLibrary.write(ground, PermissionLibrary.DELETE,
                body("table", "deny", "rule", "take(*)")).problem(), "那张表里没有这一行");
        assertNull(PermissionLibrary.write(ground, PermissionLibrary.DELETE, row).problem());
        assertTrue(store.rows(Verdict.Kind.ASK).isEmpty());
    }

    @Test
    void aRuleThatIsWrittenWrongIsRefusedWithTheMistakeAndNotAdded() {
        PermissionLibrary.Outcome outcome = PermissionLibrary.write(ground, PermissionLibrary.PUT,
                body("table", "deny", "rule", "dig(owner)"));

        assertNotNull(outcome.problem());
        assertEquals("verb", outcome.mistake().code());
        assertTrue(store.rows(Verdict.Kind.DENY).isEmpty());
        assertNotNull(PermissionLibrary.write(ground, PermissionLibrary.PUT,
                body("table", "maybe", "rule", "take(*)")).problem(), "表只有 deny、ask、allow");
    }

    @Test
    void resetClearsTheOwnersRulesAndLeavesTheModesAlone() {
        store.add(Verdict.Kind.DENY, Groups.parse("break(owner)"));
        store.setMode(blue, Mode.BYPASS);

        assertNull(PermissionLibrary.write(ground, PermissionLibrary.RESET, new JsonObject()).problem());

        assertTrue(store.rows(Verdict.Kind.DENY).isEmpty());
        assertEquals(Mode.BYPASS, store.modeOf(blue));
    }

    @Test
    void aModeIsSetOnlyOnOneOfTheOwnersCompanionsAndOnlyToAKnownMode() {
        assertNull(PermissionLibrary.write(ground, PermissionLibrary.MODE,
                body("companion", blue.toString(), "mode", "bypass")).problem());
        assertEquals(Mode.BYPASS, store.modeOf(blue));

        assertNotNull(PermissionLibrary.write(ground, PermissionLibrary.MODE,
                body("companion", UUID.randomUUID().toString(), "mode", "observe")).problem(), "不是他名下的");
        assertNotNull(PermissionLibrary.write(ground, PermissionLibrary.MODE,
                body("companion", blue.toString(), "mode", "bypas")).problem(), "写错的模式不悄悄当成 ask");
        assertNotNull(PermissionLibrary.write(ground, PermissionLibrary.MODE,
                body("companion", "not-a-uuid", "mode", "ask")).problem());
        assertEquals(Mode.BYPASS, store.modeOf(blue), "办不成的没动它");
    }
}
