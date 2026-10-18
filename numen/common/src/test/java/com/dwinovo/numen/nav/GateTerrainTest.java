package com.dwinovo.numen.nav;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.TerrainPolicy.Change;
import com.dwinovo.numen.api.permission.Actor;
import com.dwinovo.numen.api.permission.ConsentItem;
import com.dwinovo.numen.api.permission.FactoryRules;
import com.dwinovo.numen.api.permission.Gate;
import com.dwinovo.numen.api.permission.Layers;
import com.dwinovo.numen.api.permission.Hits;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.api.permission.PlacedBlocks;
import com.dwinovo.numen.api.permission.Structures;
import com.dwinovo.numen.permission.RuleSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 寻路问"这一格能不能动"时,同伴这一侧怎么答:权限层的裁决原样变成许可——放行;要问,凭据是写给主人的那一条征询;
 * 拒绝,理由是裁决本身。出厂规则下,自然方块放行,主人放的、说不清是谁的(带方块实体的箱子)要问,别人放的拒绝。
 */
@Tag("mc")
class GateTerrainTest {

    private static final BlockPos CELL = new BlockPos(3, 64, 3);
    private static final UUID OWNER = UUID.randomUUID();
    private static final Actor HER = new Actor(UUID.randomUUID(), "Aria", OWNER);

    /** 地形自己长的方块表来自数据包,无头引导下手动绑:泥土在里面。 */
    private static final TagKey<Block> NATURAL = TagKey.create(Registries.BLOCK, ResourceLocation.parse("numen:natural"));

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void bindNaturalBlocks() {
        bindBlockTags(Map.of(NATURAL, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.DIRT))));
    }

    @AfterEach
    void unbind() {
        bindBlockTags(Map.of());
    }

    private static Gate gate(PlacedBlocks placed) {
        return new Gate(HER, Layers.policy(Mode.ASK, FactoryRules.hard(), RuleSet.EMPTY, FactoryRules.layer()), placed, Structures.NONE, false, List.of(), Hits.NONE);
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
        assertTrue(item.cause().contains("of unknown origin"), item.cause());
    }

    @Test
    void theSameDirtNeedsConsentOnceTheOwnerPlacedIt() {
        PlacedBlocks placed = new PlacedBlocks();
        placed.record(CELL, new PlacedBlocks.Placer(OWNER, "Steve"));
        Permit.Ask ask = assertInstanceOf(Permit.Ask.class, dig(gate(placed), Blocks.DIRT.defaultBlockState()));
        assertTrue(((ConsentItem) ask.credential()).cause().contains("belongs to the owner"));
    }

    @Test
    void someoneElsesBlockIsAWallNoOwnerCanOpen() {
        PlacedBlocks placed = new PlacedBlocks();
        placed.record(CELL, new PlacedBlocks.Placer(UUID.randomUUID(), "Alex"));
        assertInstanceOf(Permit.Deny.class, dig(gate(placed), Blocks.DIRT.defaultBlockState()));
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
