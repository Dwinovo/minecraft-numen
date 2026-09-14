package com.dwinovo.numen.permission;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 裁决的顺序与出厂表:deny → 熔断 → allow → ask → 放行;三种模式;出厂表对自然方块放行、
 * 对玩家放的与带方块实体的问、对危险物贴着玩家的东西问。
 */
@Tag("mc")
class GateTest {

    private static boolean booted;
    private static final BlockPos POS = new BlockPos(3, 64, 3);

    @BeforeAll
    static void boot() {
        booted = FakeWorld.boot();
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过裁决钉桩");
    }

    private static Gate gate(Mode mode, RuleSet rules, PlacedBlocks placed) {
        return new Gate(null, mode, rules, placed, TerritoryClaims.NONE);
    }

    private static RuleSet rules(List<String> deny, List<String> ask, List<String> allow) {
        return new RuleSet(deny.stream().map(Rule::parse).toList(),
                ask.stream().map(Rule::parse).toList(),
                allow.stream().map(Rule::parse).toList());
    }

    // ==================== 出厂表 ====================

    @Test
    void factoryAllowsNatureAndAsksForTheOwnersThings() {
        FakeWorld world = new FakeWorld();
        PlacedBlocks placed = new PlacedBlocks();
        Gate gate = gate(Mode.ASK, RuleSet.factory(), placed);

        world.set(POS, Blocks.OAK_LOG.defaultBlockState());
        assertTrue(gate.judge(Action.breakBlock(POS, world.getBlockState(POS)), world).allowed(), "野树随便砍");

        placed.record(POS);
        Verdict v = gate.judge(Action.breakBlock(POS, world.getBlockState(POS)), world);
        assertTrue(v.asks(), "玩家放的要问");
        assertFalse(v.allowed(), "征询没接入,问 = 不许");
        assertTrue(v.reason().contains("placed by a player") && v.reason().contains(Verdict.CONSENT_NOT_WIRED));

        BlockPos chest = POS.east();
        world.set(chest, Blocks.CHEST.defaultBlockState());
        assertTrue(gate.judge(Action.breakBlock(chest, world.getBlockState(chest)), world).asks(), "带方块实体要问");
        assertTrue(gate.judge(Action.useBlock(chest, world.getBlockState(chest)), world).allowed(), "开箱子随便");
        assertTrue(gate.judge(Action.take(chest, world.getBlockState(chest), Items.DIRT), world).allowed(), "拿东西随便");
        assertTrue(gate.judge(Action.drop(Items.DIRT), world).asks(), "丢东西要问");
        assertTrue(gate.judge(Action.place(POS.above(), Blocks.AIR.defaultBlockState(), Items.DIRT), world).allowed(),
                "贴着玩家的东西放泥土随便");
        assertTrue(gate.judge(Action.place(POS.above(), Blocks.AIR.defaultBlockState(), Items.LAVA_BUCKET), world).asks(),
                "贴着玩家的东西放岩浆要问");
        assertTrue(gate.judge(Action.place(POS.offset(9, 0, 0), Blocks.AIR.defaultBlockState(), Items.LAVA_BUCKET), world)
                .allowed(), "远处放岩浆随便");
    }

    @Test
    void factoryTableTextIsWhatTheDesignSays() {
        assertEquals(List.of(
                "break(placed)", "break(block_entity)", "break(#minecraft:beds)", "break(#minecraft:doors)",
                "break(#minecraft:trapdoors)", "break(#minecraft:fence_gates)", "attack(owned)", "attack(named)",
                "attack(villager)", "drop(*)", "place(hazard_item & near_placed)"), RuleSet.FACTORY_ASK);
        assertTrue(RuleSet.factory().deny().isEmpty());
        assertTrue(RuleSet.factory().allow().isEmpty());
    }

    // ==================== 顺序 ====================

    @Test
    void denyBeatsEverythingAndAllowCarvesOutOfAsk() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.COBBLESTONE.defaultBlockState());
        PlacedBlocks placed = new PlacedBlocks();
        placed.record(POS);
        Action dig = Action.breakBlock(POS, world.getBlockState(POS));

        // "允许并记住"存下的那条更细的 allow 行,盖过出厂的 ask 行
        Gate remembered = gate(Mode.ASK,
                rules(List.of(), List.of("break(placed)"), List.of("break(placed & minecraft:cobblestone)")), placed);
        assertTrue(remembered.judge(dig, world).allowed());
        // 别的方块照旧问
        world.set(POS, Blocks.STONE.defaultBlockState());
        assertTrue(remembered.judge(Action.breakBlock(POS, world.getBlockState(POS)), world).asks());

        // deny 在最前,allow 也解不开
        Gate denied = gate(Mode.ASK,
                rules(List.of("break(placed)"), List.of(), List.of("break(*)")), placed);
        Verdict v = denied.judge(dig, world);
        assertEquals(Verdict.Kind.DENY, v.kind());
        assertTrue(v.reason().contains("break(placed)"));
    }

    @Test
    void circuitBreakerIgnoresAllowRules() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.CHEST.defaultBlockState());
        // 搜索线程读不到容器内容,contents 按"有"——于是 break(block_entity & contents) 熔断成立
        Gate gate = gate(Mode.ASK, rules(List.of(), List.of(), List.of("break(*)")), new PlacedBlocks());
        Verdict v = gate.judge(Action.breakBlock(POS, world.getBlockState(POS)), world);
        assertTrue(v.asks(), "allow break(*) 盖不住熔断");
        assertTrue(v.reason().contains("has contents"));
        // 空气格上没有熔断可言
        assertTrue(gate.judge(Action.breakBlock(POS.above(), world.getBlockState(POS.above())), world).allowed());
    }

    @Test
    void nothingMatchedMeansAllowed() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.STONE.defaultBlockState());
        Gate gate = gate(Mode.ASK, rules(List.of(), List.of(), List.of()), new PlacedBlocks());
        assertTrue(gate.judge(Action.breakBlock(POS, world.getBlockState(POS)), world).allowed());
    }

    // ==================== 模式 ====================

    @Test
    void bypassAllowsEverythingAndObserveDeniesEverything() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.CHEST.defaultBlockState());
        PlacedBlocks placed = new PlacedBlocks();
        placed.record(POS);
        Action dig = Action.breakBlock(POS, world.getBlockState(POS));

        assertTrue(gate(Mode.BYPASS, RuleSet.factory(), placed).judge(dig, world).allowed(), "bypass 连熔断都放");
        Verdict observed = gate(Mode.OBSERVE, RuleSet.factory(), placed).judge(dig, world);
        assertEquals(Verdict.Kind.DENY, observed.kind());
        assertTrue(observed.reason().contains("observe mode"));
        // observe 连自然方块也不动
        world.set(POS.north(), Blocks.DIRT.defaultBlockState());
        assertFalse(gate(Mode.OBSERVE, RuleSet.factory(), placed)
                .judge(Action.breakBlock(POS.north(), world.getBlockState(POS.north())), world).allowed());
    }
}
