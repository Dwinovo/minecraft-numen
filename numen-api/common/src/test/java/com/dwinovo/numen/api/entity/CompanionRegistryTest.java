package com.dwinovo.numen.api.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册表的持久化——这个类是"谁存在"的唯一权威,读档解析一旦失败,
 * {@code load} 会静默地退回一个<b>空</b>注册表:整个存档的同伴一次性蒸发,
 * 连报错都没有。所以每次动 codec 都必须有这一层兜着。
 */
class CompanionRegistryTest {

    private static final UUID OWNER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_OWNER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static CompanionRegistry.Entry entry(String name, UUID owner) {
        return new CompanionRegistry.Entry(name, owner, Level.OVERWORLD, new BlockPos(1, 2, 3));
    }

    private static CompanionRegistry roundTrip(CompanionRegistry reg) {
        return CompanionRegistry.load(reg.save(new CompoundTag(), null), null);
    }

    // ---- 持久化 ----

    @Test
    void entriesSurviveASaveLoadCycle() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        reg.put(B, entry("阿岩", OTHER_OWNER).withSkin("val", "sig"));

        CompanionRegistry back = roundTrip(reg);

        assertEquals("小焰", back.find(A).name());
        assertEquals(OWNER, back.find(A).owner());
        assertEquals(Level.OVERWORLD, back.find(A).dimension());
        assertEquals(new BlockPos(1, 2, 3), back.find(A).pos());

        assertEquals("val", back.find(B).skinValue(), "皮肤不能在读档时丢");
    }

    @Test
    void aSaveFromWhenDeathWaitedForARespawnTimerStillLoadsWithoutIt() {
        // 老存档里死着等复活的同伴带着 diedAt 与 deathCause。读档不能因为认不得它们就丢掉整条记录(全世界同伴蒸发),
        // 读出来她就是一只普通的同伴,下一次存盘这两个字段不再写出
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER).withSkin("val", "sig"));
        CompoundTag tag = reg.save(new CompoundTag(), null);
        CompoundTag saved = tag.getCompound("companions").getCompound(A.toString());
        saved.putLong("diedAt", 12345L);
        saved.putString("deathCause", "被苦力怕炸死了");

        CompanionRegistry back = CompanionRegistry.load(tag, null);

        assertEquals("小焰", back.find(A).name());
        assertEquals("val", back.find(A).skinValue());
        CompoundTag resaved = back.save(new CompoundTag(), null).getCompound("companions").getCompound(A.toString());
        assertFalse(resaved.contains("diedAt"));
        assertFalse(resaved.contains("deathCause"));
    }

    @Test
    void beingRemovedFromTheServerSurvivesUntilTheOwnerSummonsHerAgain() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        reg.markRemoved(A, "cheating at cards");

        CompanionRegistry back = roundTrip(reg);
        assertTrue(back.find(A).removed(), "被请出去必须活过读档——否则主人一上线就把她悄悄重建了");
        assertEquals("cheating at cards", back.find(A).removedFor());

        back.markReturned(A);
        assertFalse(roundTrip(back).find(A).removed());
    }

    @Test
    void aSaveFromBeforeTheWorldIdExistedStillLoads() {
        // 老存档里没有 worldId 这个字段。要是 codec 把它当必填,解析就会失败,
        // load 静默返回空注册表 —— 全世界的同伴一起消失。
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        CompoundTag tag = reg.save(new CompoundTag(), null);
        tag.remove("worldId");
        assertFalse(tag.contains("worldId"));

        CompanionRegistry back = CompanionRegistry.load(tag, null);

        assertEquals("小焰", back.find(A).name(), "老存档必须照常读出来");
        assertFalse(back.worldId().isBlank(), "缺的世界身份现补一个");
    }

    @Test
    void whatSheIsDoingSurvivesUnderItsName() {
        // 活的名字是派它的函数,重启后再跑的是那一行 Lua:两样都得活过读档,接不回来时才说得出她受理的是什么
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER).doing("kaleidoscope.pot.stir", "kaleidoscope.pot.stir({x = 1, y = 2, z = 3})"));

        CompanionRegistry.Entry back = roundTrip(reg).find(A);

        assertEquals("kaleidoscope.pot.stir", back.taskName());
        assertEquals("kaleidoscope.pot.stir({x = 1, y = 2, z = 3})", back.taskLua());
        assertEquals("", back.taskOld());
    }

    @Test
    void aTaskSavedAsACommandLineByAnOlderVersionIsReadButNotAsLua() {
        // 旧版本按一行命令记下的活(taskArgs):照原样读进来,只为说清它没接回来,不当成一行 Lua
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER).doing("kaleidoscope cook", "kaleidoscope.pot.stir({x = 1, y = 2, z = 3})"));
        CompoundTag tag = reg.save(new CompoundTag(), null);
        CompoundTag saved = tag.getCompound("companions").getCompound(A.toString());
        saved.remove("taskLua");
        saved.putString("taskArgs", "{\"command\":\"kaleidoscope cook 1 2 3 x\"}");

        CompanionRegistry.Entry back = CompanionRegistry.load(tag, null).find(A);

        assertEquals("kaleidoscope cook", back.taskName());
        assertEquals("", back.taskLua(), "an old command line is not a line of Lua");
        assertEquals("{\"command\":\"kaleidoscope cook 1 2 3 x\"}", back.taskOld());
    }

    @Test
    void garbageTagDegradesToEmptyRatherThanCrashing() {
        // 读档失败不该把服务器带崩;代价是这一档同伴丢了,但那是没得选的
        CompanionRegistry back = CompanionRegistry.load(new CompoundTag(), null);
        assertNull(back.find(A));
    }

    // ---- 世界身份 ----

    @Test
    void worldIdIsMintedOnceAndThenStable() {
        CompanionRegistry reg = new CompanionRegistry();
        String first = reg.worldId();

        assertFalse(first.isBlank());
        assertEquals(first, reg.worldId(), "同一个世界每次问都得是同一个答案");
        assertEquals(first, roundTrip(reg).worldId(), "读档之后也不许变");
    }

    @Test
    void twoWorldsGetDifferentIds() {
        // 这正是"换存档不会误删别的存档数据"所依赖的前提
        assertNotEquals(new CompanionRegistry().worldId(), new CompanionRegistry().worldId());
    }

    // ---- 增删改查 ----

    @Test
    void ownedByIsolatesOwners() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        reg.put(B, entry("阿岩", OTHER_OWNER));

        assertEquals(1, reg.ownedBy(OWNER).size());
        assertEquals(A, reg.ownedBy(OWNER).get(0).getKey());
        assertTrue(reg.ownedBy(UUID.randomUUID()).isEmpty());
    }

    @Test
    void removeIsPermanentAndOnlyHitsTheOne() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        reg.put(B, entry("阿岩", OWNER));

        reg.remove(A);

        assertNull(reg.find(A), "除名 = 不再存在");
        assertNull(roundTrip(reg).find(A), "读档也不许把她带回来");
        assertEquals("阿岩", reg.find(B).name(), "不许殃及别人");
        reg.remove(UUID.randomUUID());   // 删不存在的:静默
    }

    @Test
    void skinAndPositionUpdatesKeepEverythingElse() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER).doing("kaleidoscope.pot.stir", "kaleidoscope.pot.stir({x = 1, y = 2, z = 3})"));
        reg.markRemoved(A, "cheating at cards");

        // 换肤 / 挪落点都不该顺手把在做的活和被请出去的状态抹掉
        reg.put(A, reg.find(A).withSkin("v", "s"));
        reg.put(A, reg.find(A).movedTo(Level.NETHER, new BlockPos(9, 9, 9)));

        assertEquals("v", reg.find(A).skinValue());
        assertEquals(Level.NETHER, reg.find(A).dimension());
        assertEquals("小焰", reg.find(A).name());
        assertEquals("kaleidoscope.pot.stir", reg.find(A).taskName());
        assertEquals("cheating at cards", reg.find(A).removedFor());
    }
}
