package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.RuleSet;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.api.FakeWorld;

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
        assertTrue(store.add(Verdict.Kind.ASK, Groups.parse("take(*)")));
        assertFalse(store.add(Verdict.Kind.ASK, Groups.parse("take(*)")), "同一张表不重复加");
        assertTrue(store.add(Verdict.Kind.ALLOW, Groups.parse("break(owner & minecraft:cobblestone)")));
        assertTrue(store.add(Verdict.Kind.ALLOW, Groups.parse("attack(minecraft:zombie)")));
        assertTrue(before.ask().isEmpty(), "取走的快照不跟着变");
        assertSame(store.rules(), store.rules(), "没改动时是同一份快照");

        assertEquals(Groups.parse("break(owner & minecraft:cobblestone)"), store.remove(Verdict.Kind.ALLOW, 0));
        assertEquals(List.of(Groups.parse("attack(minecraft:zombie)")), store.rules().allow());
        assertThrows(IndexOutOfBoundsException.class, () -> store.remove(Verdict.Kind.DENY, 0));

        store.remember(List.of(Groups.parse("attack(minecraft:zombie)"), Groups.parse("drop(minecraft:dirt)")));
        assertEquals(List.of(Groups.parse("attack(minecraft:zombie)"), Groups.parse("drop(minecraft:dirt)")),
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
        store.add(Verdict.Kind.DENY, Groups.parse("break(#minecraft:beds)"));
        store.add(Verdict.Kind.ASK, Groups.parse("break(!owner & !block_entity)"));
        store.add(Verdict.Kind.ALLOW, Groups.parse("break(block_entity & minecraft:chest & !contents)"));

        PermissionStore loaded = PermissionStore.load(store.save(new CompoundTag(), null), null);
        assertEquals(Mode.BYPASS, loaded.modeOf(companion));
        assertEquals(store.rules().deny(), loaded.rules().deny());
        assertEquals(store.rules().ask(), loaded.rules().ask());
        assertEquals(store.rules().allow(), loaded.rules().allow());
    }

    @Test
    void rowsRememberWhoWroteThemAndWhySoOldBareLinesAreTheOwners() {
        PermissionStore store = new PermissionStore();
        store.add(Verdict.Kind.DENY, Groups.parse("break(owner)"));
        store.add(Verdict.Kind.DENY, new PermissionStore.Row(Groups.parse("break(owner & #c:chests)"),
                PermissionStore.Source.HER, "别动箱子"));
        assertFalse(store.add(Verdict.Kind.DENY, new PermissionStore.Row(Groups.parse("break(owner)"),
                PermissionStore.Source.HER, "")), "同一条规则不因来源不同重复加");

        PermissionStore loaded = PermissionStore.load(store.save(new CompoundTag(), null), null);
        assertEquals(PermissionStore.Source.OWNER, loaded.rows(Verdict.Kind.DENY).get(0).source());
        assertEquals(PermissionStore.Source.HER, loaded.rows(Verdict.Kind.DENY).get(1).source());
        assertEquals("别动箱子", loaded.rows(Verdict.Kind.DENY).get(1).note());
        assertEquals(store.rules().deny(), loaded.rules().deny());
        assertEquals(1, loaded.indexOf(Verdict.Kind.DENY, Groups.parse("break(owner & #c:chests)")));
        assertEquals(-1, loaded.indexOf(Verdict.Kind.ASK, Groups.parse("break(owner)")));

        CompoundTag old = new CompoundTag();
        old.put("modes", new CompoundTag());
        ListTag deny = new ListTag();
        deny.add(StringTag.valueOf("break(owner)"));
        old.put("deny", deny);
        assertEquals(PermissionStore.Source.OWNER,
                PermissionStore.load(old, null).rows(Verdict.Kind.DENY).get(0).source(), "只有原文的旧行是主人写的");
    }

    @Test
    void hitsAreCountedPerLayerTableAndRuleAndClearedWithTheRow() {
        PermissionStore store = new PermissionStore();
        Rule mine = Groups.parse("break(owner)");
        store.add(Verdict.Kind.ASK, mine);
        assertEquals(PermissionStore.Hit.NEVER, store.hitsOf(Layers.OWNER, Verdict.Kind.ASK, mine));

        store.hit(Layers.OWNER, Verdict.Kind.ASK, mine);
        store.hit(Layers.OWNER, Verdict.Kind.ASK, mine);
        store.hit(Layers.FACTORY, Verdict.Kind.ASK, mine);
        store.hit(Verdict.HARD, Verdict.Kind.DENY, Groups.parse("break(other)"));
        assertEquals(2, store.hitsOf(Layers.OWNER, Verdict.Kind.ASK, mine).count());
        assertTrue(store.hitsOf(Layers.OWNER, Verdict.Kind.ASK, mine).lastMillis() > 0);
        assertEquals(1, store.hitsOf(Layers.FACTORY, Verdict.Kind.ASK, mine).count(), "另一层的同名规则是另一本账");
        assertEquals(0, store.hitsOf(Layers.OWNER, Verdict.Kind.DENY, mine).count(), "另一张表也是");

        PermissionStore loaded = PermissionStore.load(store.save(new CompoundTag(), null), null);
        assertEquals(store.hitsOf(Layers.OWNER, Verdict.Kind.ASK, mine), loaded.hitsOf(Layers.OWNER, Verdict.Kind.ASK, mine),
                "账跟着存档来回");
        assertEquals(1, loaded.hitsOf(Verdict.HARD, Verdict.Kind.DENY, Groups.parse("break(other)")).count());

        store.remove(Verdict.Kind.ASK, 0);
        assertEquals(0, store.hitsOf(Layers.OWNER, Verdict.Kind.ASK, mine).count(), "删掉的行,账一起清零");
        assertEquals(1, store.hitsOf(Layers.FACTORY, Verdict.Kind.ASK, mine).count(), "别层的账不动");
        store.add(Verdict.Kind.ASK, mine);
        store.hit(Layers.OWNER, Verdict.Kind.ASK, mine);
        store.reset();
        assertEquals(0, store.hitsOf(Layers.OWNER, Verdict.Kind.ASK, mine).count(), "清空主人层,它的账也清");
        assertEquals(1, store.hitsOf(Verdict.HARD, Verdict.Kind.DENY, Groups.parse("break(other)")).count(),
                "硬规则与出厂层的账不动");
    }

    @Test
    void aSaveWithoutTablesLoadsEmptyRules() {
        CompoundTag tag = new CompoundTag();
        tag.put("modes", new CompoundTag());
        PermissionStore loaded = PermissionStore.load(tag, null);
        assertTrue(loaded.rules().allow().isEmpty(), "只存过模式的档照读");
    }

    @Test
    void oldNamedRowsAreRewrittenOnLoadAndSavedBack() {
        CompoundTag tag = new CompoundTag();
        tag.put("modes", new CompoundTag());
        ListTag ask = new ListTag();
        ask.add(StringTag.valueOf("break(placed & !block_entity)"));
        ask.add(StringTag.valueOf("attack(owned)"));
        tag.put("ask", ask);
        ListTag allow = new ListTag();
        allow.add(StringTag.valueOf("break(self_placed & !contents)"));
        allow.add(StringTag.valueOf("place(hazard_item & !near_placed)"));
        allow.add(StringTag.valueOf("break(minecraft:placed_thing)"));
        tag.put("allow", allow);

        PermissionStore loaded = PermissionStore.load(tag, null);
        assertEquals(List.of(Groups.parse("break((owner | other) & !block_entity)"),
                Groups.parse("attack(self | owner | other)")), loaded.rules().ask());
        assertEquals(List.of(Groups.parse("break(self & !contents)"),
                Groups.parse("place(hazard_item & !(near(owner) | near(other)))"),
                Groups.parse("break(minecraft:placed_thing)")), loaded.rules().allow(),
                "id 里碰巧同名的部分不动");
        assertTrue(loaded.isDirty(), "改写过的档要重新落盘");
        assertEquals("break((owner | other) & !block_entity)", loaded.rules().ask().get(0).toString());
        PermissionStore again = PermissionStore.load(loaded.save(new CompoundTag(), null), null);
        assertEquals(loaded.rules().ask(), again.rules().ask());
        assertFalse(again.isDirty(), "新写法的档读档不再改写");
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
        assertEquals(List.of(Groups.parse("break(minecraft:dirt)"), Groups.parse("take(*)")), loaded.rules().allow());
    }
}
