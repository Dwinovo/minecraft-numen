package com.dwinovo.numen.nav;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.TerrainPolicy.Change;
import com.dwinovo.numen.api.permission.Actor;
import com.dwinovo.numen.api.permission.ConsentItem;
import com.dwinovo.numen.api.permission.Gate;
import com.dwinovo.numen.api.permission.Owners;
import com.dwinovo.numen.permission.Settings;
import com.dwinovo.numen.permission.Tier;
import com.dwinovo.numen.permission.Verb;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 寻路问"这一格能不能动"时,同伴这一侧怎么答:权限层的裁决原样变成许可——放行;要问,凭据是写给主人的那一条征询;
 * 拒绝,理由是裁决本身。出厂设置下,没主人的方块放行,主人的要问,别人的拒绝;主人把挖定成问,没主人的也要问。
 */
@Tag("mc")
class GateTerrainTest {

    private static final BlockPos CELL = new BlockPos(3, 64, 3);
    private static final UUID OWNER = UUID.randomUUID();
    private static final Actor HER = new Actor(UUID.randomUUID(), "Aria", OWNER);

    /** 装上 Numen 的全部登记再问,和真的游戏里一样。 */
    @BeforeAll
    static void boot() {
        com.dwinovo.numen.NumenApiFixture.install();
    }

    /** 出厂设置的裁决快照;{@code owners} 是哪一格是谁的,没写的格没人说。 */
    private static Gate gate(Map<BlockPos, UUID> owners) {
        return gate(Settings.FACTORY, owners);
    }

    private static Gate gate(Settings settings, Map<BlockPos, UUID> owners) {
        return new Gate(HER, settings, Owners.Blocks.of(List.of(owners::get)), List.of());
    }

    private static Permit dig(Gate gate, BlockState state) {
        View view = new View();
        view.set(CELL, state);
        return new GateTerrain(gate).judge(Change.DIG, CELL, state, view);
    }

    @Test
    void blocksThatAreNobodysMayBeDug() {
        assertInstanceOf(Permit.Allow.class, dig(gate(Map.of()), Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void aTierOfAskMakesEvenNobodysBlockNeedConsentAndTheCredentialSaysWhy() {
        Settings asking = Settings.FACTORY.withTier(Verb.BREAK, Tier.ASK);
        Permit permit = dig(gate(asking, Map.of()), Blocks.CHEST.defaultBlockState());
        Permit.Ask ask = assertInstanceOf(Permit.Ask.class, permit);
        ConsentItem item = assertInstanceOf(ConsentItem.class, ask.credential());
        assertTrue(item.cause().contains("setting for break is ask"), item.cause());
    }

    @Test
    void theSameDirtNeedsConsentOnceItIsTheOwners() {
        Permit.Ask ask = assertInstanceOf(Permit.Ask.class, dig(gate(Map.of(CELL, OWNER)), Blocks.DIRT.defaultBlockState()));
        assertTrue(((ConsentItem) ask.credential()).cause().contains("belongs to the owner"));
    }

    @Test
    void aVerbTheOwnerSetToDenyMakesTheCellAWall() {
        Settings denying = Settings.FACTORY.withTier(Verb.BREAK, Tier.DENY);
        assertInstanceOf(Permit.Deny.class, dig(gate(denying, Map.of()), Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void placingIntoOpenAirIsAllowed() {
        View view = new View();
        Permit permit = new GateTerrain(gate(Map.of()))
                .judge(new Change.Place(Blocks.DIRT), CELL, Blocks.AIR.defaultBlockState(), view);
        assertEquals(Permit.ALLOW, permit);
    }

    private static final class View implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
}
