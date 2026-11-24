package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.FakeWorld;
import com.dwinovo.numen.permission.Edit;
import com.dwinovo.numen.permission.Tier;
import com.dwinovo.numen.permission.Verb;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 面板的权限页读写的那一种库:整表是名下每只同伴每种动作一行档位、加名单里的每一项一行;设档位、套预设、从名单里删一项都落在
 * 主人的存档上,认不出的动作与档位办不成、不改任何东西。
 */
@Tag("mc")
class PermissionLibraryTest {

    private static boolean booted;

    private final PermissionStore store = new PermissionStore();
    private final UUID blue = UUID.randomUUID();
    private final Map<UUID, String> companions = new LinkedHashMap<>(Map.of(blue, "小蓝"));

    private final PermissionLibrary.Ground ground = new PermissionLibrary.Ground() {
        @Override
        public PermissionStore store() {
            return store;
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

    private static JsonObject body(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            o.addProperty(kv[i], kv[i + 1]);
        }
        return o;
    }

    @Test
    void theTableHasEightTierRowsPerCompanionThenTheListedNames() {
        store.edit(blue, new Edit.SetTier(Verb.BREAK, Tier.DENY));
        store.edit(blue, new Edit.Add(Verb.COMMAND, "msg"));

        JsonArray rows = PermissionLibrary.view(ground);

        assertEquals(9, rows.size());
        JsonObject first = rows.get(0).getAsJsonObject();
        assertEquals("tier", first.get("row").getAsString());
        assertEquals(blue.toString(), first.get("companion").getAsString());
        assertEquals("小蓝", first.get("name").getAsString());
        assertEquals("break", first.get("verb").getAsString());
        assertEquals("deny", first.get("tier").getAsString());
        assertEquals("use_item", rows.get(6).getAsJsonObject().get("verb").getAsString(), "第七个动作是用手里的东西");
        JsonObject listed = rows.get(8).getAsJsonObject();
        assertEquals("listed", listed.get("row").getAsString());
        assertEquals("command", listed.get("verb").getAsString());
        assertEquals("msg", listed.get("entry").getAsString());
    }

    @Test
    void settingATierApplyingAPresetAndDeletingAnEntryLandOnTheStore() {
        assertNull(PermissionLibrary.write(ground, PermissionLibrary.TIER,
                body("companion", blue.toString(), "verb", "drop", "tier", "allow")).problem());
        assertEquals(Tier.ALLOW, store.settingsOf(blue).tier(Verb.DROP));

        assertNull(PermissionLibrary.write(ground, PermissionLibrary.PRESET,
                body("companion", blue.toString(), "preset", "observe")).problem());
        assertEquals(Tier.DENY, store.settingsOf(blue).tier(Verb.BREAK));

        store.edit(blue, new Edit.Add(Verb.BREAK, "minecraft:stone"));
        assertNull(PermissionLibrary.write(ground, PermissionLibrary.DELETE,
                body("companion", blue.toString(), "verb", "break", "entry", "minecraft:stone")).problem());
        assertTrue(store.settingsOf(blue).names(Verb.BREAK).isEmpty());
    }

    @Test
    void whatItDoesNotUnderstandIsRefusedAndNothingChanges() {
        assertNotNull(PermissionLibrary.write(ground, PermissionLibrary.TIER,
                body("companion", blue.toString(), "verb", "dig", "tier", "deny")).problem());
        assertNotNull(PermissionLibrary.write(ground, PermissionLibrary.TIER,
                body("companion", blue.toString(), "verb", "break", "tier", "maybe")).problem());
        assertNotNull(PermissionLibrary.write(ground, PermissionLibrary.PRESET,
                body("companion", blue.toString(), "preset", "wild")).problem());
        assertNotNull(PermissionLibrary.write(ground, PermissionLibrary.DELETE,
                body("companion", blue.toString(), "verb", "break", "entry", "minecraft:stone")).problem(), "名单里没有这一项");
        assertNotNull(PermissionLibrary.write(ground, PermissionLibrary.TIER,
                body("companion", UUID.randomUUID().toString(), "verb", "break", "tier", "deny")).problem(), "不是他的同伴");
        assertEquals(com.dwinovo.numen.permission.Settings.FACTORY, store.settingsOf(blue));
    }
}
