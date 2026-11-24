package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.FakeWorld;
import com.dwinovo.numen.permission.Edit;
import com.dwinovo.numen.permission.Preset;
import com.dwinovo.numen.permission.Settings;
import com.dwinovo.numen.permission.Tier;
import com.dwinovo.numen.permission.Verb;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 主人给每只同伴定的设置的家:没设过是出厂的;改动、预设、"以后都允许"换一份新的不可变设置;存档读档一个来回原样回来;
 * 更早的规则与模式那一种存档读不通——不生效、不迁移。需要 MC 注册表(存档类要引导)。
 */
@Tag("mc")
class PermissionStoreTest {

    private static boolean booted;

    @BeforeAll
    static void boot() {
        booted = FakeWorld.boot();
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过权限存档钉桩");
    }

    @Test
    void aCompanionNobodySetUpHasTheFactorySettings() {
        PermissionStore store = new PermissionStore();
        assertEquals(Settings.FACTORY, store.settingsOf(UUID.randomUUID()));
    }

    @Test
    void editsPresetsAndRememberingChangeOnlyThatCompanion() {
        PermissionStore store = new PermissionStore();
        UUID aria = UUID.randomUUID();
        UUID bea = UUID.randomUUID();

        store.edit(aria, new Edit.SetTier(Verb.BREAK, Tier.DENY));
        store.remember(aria, List.of(new Settings.Entry(Verb.COMMAND, "msg"), new Settings.Entry(Verb.COMMAND, "msg")));
        assertEquals(Tier.DENY, store.settingsOf(aria).tier(Verb.BREAK));
        assertEquals(List.of("msg"), store.settingsOf(aria).names(Verb.COMMAND), "记住不重复");
        assertEquals(Settings.FACTORY, store.settingsOf(bea), "别的同伴不动");

        store.preset(aria, Preset.BYPASS);
        assertEquals(Tier.ALLOW, store.settingsOf(aria).tier(Verb.DROP));
        assertEquals(List.of("msg"), store.settingsOf(aria).names(Verb.COMMAND), "全放行不动名单");
        store.preset(aria, Preset.FACTORY);
        assertEquals(Settings.FACTORY, store.settingsOf(aria));
    }

    @Test
    void aSaveRoundTripsEveryCompanionsTiersAndLists() {
        PermissionStore store = new PermissionStore();
        UUID aria = UUID.randomUUID();
        UUID bea = UUID.randomUUID();
        store.edit(aria, new Edit.SetTier(Verb.ATTACK, Tier.ASK));
        store.edit(aria, new Edit.SetTier(Verb.DROP, Tier.ALLOW));
        store.remember(aria, List.of(new Settings.Entry(Verb.BREAK, "minecraft:stone"),
                new Settings.Entry(Verb.BREAK, "minecraft:dirt"), new Settings.Entry(Verb.ATTACK, "minecraft:zombie")));
        store.preset(bea, Preset.OBSERVE);

        PermissionStore loaded = PermissionStore.load(store.save(new CompoundTag(), null), null);

        assertEquals(store.settingsOf(aria), loaded.settingsOf(aria));
        assertEquals(store.settingsOf(bea), loaded.settingsOf(bea));
        assertEquals(List.of("minecraft:stone", "minecraft:dirt"), loaded.settingsOf(aria).names(Verb.BREAK),
                "名单的先后也回来");
        assertEquals(Settings.FACTORY, loaded.settingsOf(UUID.randomUUID()));
    }

    @Test
    void anOlderSaveWithRulesAndModesIsNotUsed() {
        CompoundTag tag = new CompoundTag();
        CompoundTag modes = new CompoundTag();
        modes.putString(UUID.randomUUID().toString(), "observe");
        tag.put("modes", modes);
        ListTag ask = new ListTag();
        ask.add(StringTag.valueOf("break(owner)"));
        tag.put("ask", ask);

        PermissionStore loaded = PermissionStore.load(tag, null);

        for (UUID companion : List.of(UUID.randomUUID(), UUID.randomUUID())) {
            assertEquals(Settings.FACTORY, loaded.settingsOf(companion));
        }
        assertFalse(loaded.isDirty(), "读档不为丢掉的内容重写存档");
        assertTrue(PermissionStore.load(loaded.save(new CompoundTag(), null), null).save(new CompoundTag(), null)
                .getCompound("companions").isEmpty(), "再存盘时旧内容就没有了");
    }

    @Test
    void anEntryWeDoNotKnowIsLeftOutAndTheRestLoads() {
        UUID aria = UUID.randomUUID();
        CompoundTag tiers = new CompoundTag();
        tiers.putString("break", "deny");
        tiers.putString("dig", "deny");
        tiers.putString("drop", "maybe");
        CompoundTag one = new CompoundTag();
        one.put("tiers", tiers);
        CompoundTag companions = new CompoundTag();
        companions.put(aria.toString(), one);
        companions.put("not-a-uuid", new CompoundTag());
        CompoundTag tag = new CompoundTag();
        tag.put("companions", companions);

        PermissionStore loaded = PermissionStore.load(tag, null);

        assertEquals(Tier.DENY, loaded.settingsOf(aria).tier(Verb.BREAK));
        assertEquals(Tier.ASK, loaded.settingsOf(aria).tier(Verb.DROP), "读不通的档位按出厂的");
    }
}
