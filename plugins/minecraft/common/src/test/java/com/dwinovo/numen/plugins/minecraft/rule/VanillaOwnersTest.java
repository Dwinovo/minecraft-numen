package com.dwinovo.numen.plugins.minecraft.rule;

import com.dwinovo.numen.plugins.minecraft.NumenApiFixture;
import com.dwinovo.numen.api.permission.Owners;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.OwnableEntity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原版驯服记录的回答者:记着主人的说那个主人,没驯服的、不是可驯服的实体不说;经登记的门问一圈问到的就是它。主人是世界里一只活着的
 * 非玩家时按没主算的那一条要活世界才判得了,在 GameTest 里验。需要 MC 注册表。
 */
@Tag("mc")
class VanillaOwnersTest {

    @BeforeAll
    static void boot() {
        NumenApiFixture.install();
    }

    /** 一只不在任何世界里的实体,只有"是哪一类"这一件事。 */
    private static class Wild extends Entity {
        Wild() {
            super(EntityType.PIG, null);
        }

        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { }
        @Override protected void readAdditionalSaveData(CompoundTag tag) { }
        @Override protected void addAdditionalSaveData(CompoundTag tag) { }
    }

    /** 同上,带一条原版的驯服记录。 */
    private static final class Pet extends Wild implements OwnableEntity {
        private final UUID owner;

        Pet(UUID owner) {
            this.owner = owner;
        }

        @Override public UUID getOwnerUUID() {
            return owner;
        }
    }

    @Test
    void theTamingRecordNamesTheOwner() {
        UUID tamer = UUID.randomUUID();
        assertEquals(tamer, VanillaOwners.tamer(new Pet(tamer)));
        assertNull(VanillaOwners.tamer(new Pet(null)), "还没驯服");
        assertNull(VanillaOwners.tamer(new Wild()), "不是可驯服的实体");
    }

    @Test
    void askingAroundReachesIt() {
        UUID tamer = UUID.randomUUID();
        assertEquals(List.of(tamer), Owners.of(new Pet(tamer)));
        assertTrue(Owners.of(new Wild()).isEmpty(), "没人说它是谁的");
    }
}
