package com.dwinovo.numen.entity;

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
        return CompanionRegistry.load(reg.save(new CompoundTag()));
    }

    // ---- 持久化 ----

    @Test
    void entriesSurviveASaveLoadCycle() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        reg.put(B, entry("阿岩", OTHER_OWNER).withSkin("val", "sig"));
        reg.markDead(B, "被苦力怕炸死了", 12345L);

        CompanionRegistry back = roundTrip(reg);

        assertEquals("小焰", back.find(A).name());
        assertEquals(OWNER, back.find(A).owner());
        assertEquals(Level.OVERWORLD, back.find(A).dimension());
        assertEquals(new BlockPos(1, 2, 3), back.find(A).pos());

        assertEquals("val", back.find(B).skinValue(), "皮肤不能在读档时丢");
        assertEquals(12345L, back.find(B).diedAt(), "死亡状态必须活过读档——否则重登会当作没死过");
        assertEquals("被苦力怕炸死了", back.find(B).deathCause());
    }

    @Test
    void aSaveFromBeforeTheWorldIdExistedStillLoads() {
        // 老存档里没有 worldId 这个字段。要是 codec 把它当必填,解析就会失败,
        // load 静默返回空注册表 —— 全世界的同伴一起消失。
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        CompoundTag tag = reg.save(new CompoundTag());
        tag.remove("worldId");
        assertFalse(tag.contains("worldId"));

        CompanionRegistry back = CompanionRegistry.load(tag);

        assertEquals("小焰", back.find(A).name(), "老存档必须照常读出来");
        assertFalse(back.worldId().isBlank(), "缺的世界身份现补一个");
    }

    @Test
    void garbageTagDegradesToEmptyRatherThanCrashing() {
        // 读档失败不该把服务器带崩;代价是这一档同伴丢了,但那是没得选的
        CompanionRegistry back = CompanionRegistry.load(new CompoundTag());
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
    void deathAndRespawnFlipTheSameFlag() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        assertTrue(reg.pendingDead().isEmpty());

        reg.markDead(A, "掉下去了", 999L);
        assertEquals(1, reg.pendingDead().size());
        assertEquals(999L, reg.find(A).diedAt());

        reg.markAlive(A);
        assertTrue(reg.pendingDead().isEmpty(), "复活了就不该再排队等复活");
        assertEquals(0L, reg.find(A).diedAt());
        assertEquals("", reg.find(A).deathCause());
    }

    @Test
    void deathStateOfAnUnknownCompanionIsANoOp() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.markDead(UUID.randomUUID(), "x", 1L);
        reg.markAlive(UUID.randomUUID());
        assertTrue(reg.pendingDead().isEmpty());
    }

    @Test
    void skinAndPositionUpdatesKeepEverythingElse() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        reg.markDead(A, "淹死了", 500L);

        // 换肤 / 挪落点都不该顺手把死亡状态抹掉——抹掉她就永远不会复活了
        reg.put(A, reg.find(A).withSkin("v", "s"));
        reg.put(A, reg.find(A).movedTo(Level.NETHER, new BlockPos(9, 9, 9)));

        assertEquals(500L, reg.find(A).diedAt());
        assertEquals("淹死了", reg.find(A).deathCause());
        assertEquals("v", reg.find(A).skinValue());
        assertEquals(Level.NETHER, reg.find(A).dimension());
        assertEquals("小焰", reg.find(A).name());
    }
}
