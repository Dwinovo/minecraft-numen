package com.dwinovo.numen.core.nav;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.TerrainPolicy.Change;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.Gate;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.PlacedBlocks;
import com.dwinovo.numen.permission.RuleSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
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
 * 拒绝,理由是裁决本身。出厂规则下,自然方块放行,带方块实体的(箱子)、玩家放的、门标签里的要问。
 */
@Tag("mc")
class GateTerrainTest {

    private static final BlockPos CELL = new BlockPos(3, 64, 3);

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static Gate gate(PlacedBlocks placed) {
        return new Gate(null, Mode.ASK, RuleSet.EMPTY, RuleSet.factory(),
                placed, List.of());
    }

    private static Permit dig(Gate gate, BlockState state) {
        View view = new View();
        view.set(CELL, state);
        return new GateTerrain(gate).judge(Change.DIG, CELL, state, view);
    }

    @Test
    void naturalBlocksMayBeDug() {
        assertInstanceOf(Permit.Allow.class, dig(gate(new PlacedBlocks()), Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void aChestNeedsConsentAndTheCredentialSaysWhy() {
        Permit permit = dig(gate(new PlacedBlocks()), Blocks.CHEST.defaultBlockState());
        Permit.Ask ask = assertInstanceOf(Permit.Ask.class, permit);
        ConsentItem item = assertInstanceOf(ConsentItem.class, ask.credential());
        assertTrue(item.cause().contains("has a block entity"), item.cause());
    }

    @Test
    void theSameDirtNeedsConsentOnceAPlayerPlacedIt() {
        PlacedBlocks placed = new PlacedBlocks();
        placed.record(CELL, new PlacedBlocks.Placer(UUID.randomUUID(), "Steve"));
        Permit.Ask ask = assertInstanceOf(Permit.Ask.class, dig(gate(placed), Blocks.DIRT.defaultBlockState()));
        assertTrue(((ConsentItem) ask.credential()).cause().contains("placed by a player"));
    }

    /** 出厂表点名的是门标签;标签内容运行时来自数据包,这里把石头绑进去,钉的是"标签那一行生效"。 */
    @Test
    void theFactoryDoorRowCoversWhateverTheDoorTagHolds() {
        bindBlockTags(Map.of(BlockTags.DOORS, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.STONE))));
        try {
            assertInstanceOf(Permit.Ask.class, dig(gate(new PlacedBlocks()), Blocks.STONE.defaultBlockState()));
        } finally {
            bindBlockTags(Map.of());
        }
    }

    @Test
    void placingIntoOpenAirIsAllowed() {
        View view = new View();
        Permit permit = new GateTerrain(gate(new PlacedBlocks()))
                .judge(new Change.Place(Blocks.DIRT), CELL, Blocks.AIR.defaultBlockState(), view);
        assertEquals(Permit.ALLOW, permit);
    }

    @SuppressWarnings("unchecked")
    private static void bindBlockTags(Map<TagKey<Block>, List<Holder<Block>>> tags) {
        ((MappedRegistry<Block>) BuiltInRegistries.BLOCK).bindTags(tags);
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
