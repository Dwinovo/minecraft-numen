package com.dwinovo.numen.permission;

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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 主人那一层规则的家:加、删、清空、记住不重复;改动换一份新的不可变快照;模式与三张表一起存档读档,
 * 存档里写错的行读档时不进表、别的行照读。需要 MC 注册表(信号名解析读物品表)。
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
    void rowsAreAddedOnceRemovedByIndexAndResetClearsOnlyTheRules() {
        PermissionStore store = new PermissionStore();
        UUID companion = UUID.randomUUID();
        store.setMode(companion, Mode.OBSERVE);

        RuleSet before = store.rules();
        assertTrue(store.add(Verdict.Kind.ASK, Rule.parse("take(*)")));
        assertFalse(store.add(Verdict.Kind.ASK, Rule.parse("take(*)")), "同一张表不重复加");
        assertTrue(store.add(Verdict.Kind.ALLOW, Rule.parse("break(placed & minecraft:cobblestone)")));
        assertTrue(store.add(Verdict.Kind.ALLOW, Rule.parse("attack(minecraft:zombie)")));
        assertTrue(before.ask().isEmpty(), "取走的快照不跟着变");
        assertSame(store.rules(), store.rules(), "没改动时是同一份快照");

        assertEquals(Rule.parse("break(placed & minecraft:cobblestone)"), store.remove(Verdict.Kind.ALLOW, 0));
        assertEquals(List.of(Rule.parse("attack(minecraft:zombie)")), store.rules().allow());
        assertThrows(IndexOutOfBoundsException.class, () -> store.remove(Verdict.Kind.DENY, 0));

        store.remember(List.of(Rule.parse("attack(minecraft:zombie)"), Rule.parse("drop(minecraft:dirt)")));
        assertEquals(List.of(Rule.parse("attack(minecraft:zombie)"), Rule.parse("drop(minecraft:dirt)")),
                store.rules().allow(), "记住的行进 allow 表,已有的不重复");

        store.reset();
        assertTrue(store.rules().deny().isEmpty() && store.rules().ask().isEmpty() && store.rules().allow().isEmpty());
        assertEquals(Mode.OBSERVE, store.modeOf(companion), "清空规则不动模式");
    }

    @Test
    void modesAndTablesSurviveASaveAndLoad() {
        PermissionStore store = new PermissionStore();
        UUID companion = UUID.randomUUID();
        store.setMode(companion, Mode.BYPASS);
        store.add(Verdict.Kind.DENY, Rule.parse("break(#minecraft:beds)"));
        store.add(Verdict.Kind.ASK, Rule.parse("break(!placed & !block_entity)"));
        store.add(Verdict.Kind.ALLOW, Rule.parse("break(block_entity & minecraft:chest & !contents)"));

        PermissionStore loaded = PermissionStore.load(store.save(new CompoundTag(), null), null);
        assertEquals(Mode.BYPASS, loaded.modeOf(companion));
        assertEquals(store.rules().deny(), loaded.rules().deny());
        assertEquals(store.rules().ask(), loaded.rules().ask());
        assertEquals(store.rules().allow(), loaded.rules().allow());
    }

    @Test
    void aSaveWithoutTablesLoadsEmptyRules() {
        CompoundTag tag = new CompoundTag();
        tag.put("modes", new CompoundTag());
        PermissionStore loaded = PermissionStore.load(tag, null);
        assertTrue(loaded.rules().allow().isEmpty(), "只存过模式的档照读");
    }

    @Test
    void aMistypedLineInTheSaveIsLeftOutAndTheRestLoads() {
        CompoundTag tag = new CompoundTag();
        tag.put("modes", new CompoundTag());
        ListTag allow = new ListTag();
        allow.add(StringTag.valueOf("break(minecraft:dirt)"));
        allow.add(StringTag.valueOf("break(haunted)"));
        allow.add(StringTag.valueOf("take(*)"));
        tag.put("allow", allow);
        PermissionStore loaded = PermissionStore.load(tag, null);
        assertEquals(List.of(Rule.parse("break(minecraft:dirt)"), Rule.parse("take(*)")), loaded.rules().allow());
    }
}
