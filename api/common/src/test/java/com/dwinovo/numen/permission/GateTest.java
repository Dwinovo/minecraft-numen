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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 裁决的顺序与出厂表:模式 → 外部强制 → deny → allow → ask → 都不中也问;出厂 allow 行放行自然方块,
 * 出厂 ask 行问玩家放的与带方块实体的;三种模式;任务期授权按"同一行规则 + 同一种东西"放行。
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
        return new Gate(null, mode, rules, placed, TerritoryClaims.NONE, List.of());
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
        assertTrue(gate.judge(Action.breakBlock(POS, world.getBlockState(POS)), world).allowed(),
                "野树由出厂 allow 行放行");

        placed.record(POS);
        Verdict v = gate.judge(Action.breakBlock(POS, world.getBlockState(POS)), world);
        assertTrue(v.asks(), "玩家放的要问");
        assertFalse(v.allowed(), "问 ≠ 放行");
        assertEquals("break(placed)", v.rule().toString());
        assertTrue(v.reason().contains("placed by a player"));

        BlockPos chest = POS.east();
        world.set(chest, Blocks.CHEST.defaultBlockState());
        Verdict chestVerdict = gate.judge(Action.breakBlock(chest, world.getBlockState(chest)), world);
        assertTrue(chestVerdict.asks(), "带方块实体要问");
        assertEquals("break(block_entity & contents)", chestVerdict.rule().toString(),
                "搜索线程读不到内容按有:装着东西的那一行更具体,排在前面");
        assertTrue(chestVerdict.rule().irreversible(), "拆装着东西的容器撤不回");
        assertTrue(gate.judge(Action.useBlock(chest, world.getBlockState(chest)), world).allowed(), "开箱子随便");
        assertTrue(gate.judge(Action.take(chest, world.getBlockState(chest), Items.DIRT), world).allowed(), "拿东西随便");
        assertTrue(gate.judge(Action.drop(Items.DIRT), world).asks(), "丢东西要问");
        assertTrue(gate.judge(Action.place(POS.above(), Blocks.AIR.defaultBlockState(), Items.DIRT), world).allowed(),
                "贴着玩家的东西放泥土随便");
        assertTrue(gate.judge(Action.place(POS.above(), Blocks.AIR.defaultBlockState(), Items.LAVA_BUCKET), world).asks(),
                "贴着玩家的东西放岩浆要问");
        assertTrue(gate.judge(Action.place(POS.offset(9, 0, 0), Blocks.AIR.defaultBlockState(), Items.LAVA_BUCKET), world)
                .allowed(), "远处放岩浆随便");
        assertTrue(gate.judge(Action.place(POS.above(), Blocks.AIR.defaultBlockState(), null), world).allowed(),
                "规划期还不知道放什么:不危险那一行放行");
    }

    @Test
    void factoryTablesAreWhatTheDesignSays() {
        assertEquals(List.of(
                "break(block_entity & contents)", "break(placed)", "break(block_entity)", "break(#minecraft:beds)",
                "break(#minecraft:doors)", "break(#minecraft:trapdoors)", "break(#minecraft:fence_gates)",
                "attack(owned)", "attack(named)", "attack(villager)", "drop(*)",
                "place(hazard_item & near_placed)"), RuleSet.FACTORY_ASK);
        assertEquals(List.of(
                "break(!placed & !block_entity & !#minecraft:beds & !#minecraft:doors"
                        + " & !#minecraft:trapdoors & !#minecraft:fence_gates)",
                "place(!hazard_item)", "place(hazard_item & !near_placed)",
                "attack(!owned & !named & !villager)", "use_block(*)", "use_entity(!owned)", "take(*)"),
                RuleSet.FACTORY_ALLOW);
        assertTrue(RuleSet.factory().deny().isEmpty(), "出厂不写死任何拒绝");
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
    void theIrreversibleRowsAreOrdinaryAskRowsThatAllowCanCover() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.CHEST.defaultBlockState());
        // 没有熔断:主人写的 allow 行照样盖得住"拆装着东西的箱子"
        Gate gate = gate(Mode.ASK, rules(List.of(), RuleSet.FACTORY_ASK, List.of("break(*)")), new PlacedBlocks());
        assertTrue(gate.judge(Action.breakBlock(POS, world.getBlockState(POS)), world).allowed());
        assertTrue(Rule.parse("attack(owned)").irreversible());
        assertFalse(Rule.parse("attack(named)").irreversible());
        assertFalse(Rule.parse("break(!contents)").irreversible(), "取反的撤不回信号不算");
    }

    @Test
    void nothingMatchedMeansAsk() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.STONE.defaultBlockState());
        Gate gate = gate(Mode.ASK, rules(List.of(), List.of(), List.of()), new PlacedBlocks());
        Verdict v = gate.judge(Action.breakBlock(POS, world.getBlockState(POS)), world);
        assertTrue(v.asks(), "没有一行规则说过的事去问主人,不放行");
        assertNull(v.rule());
        assertEquals(Verdict.UNCOVERED, v.cause());
    }

    // ==================== 外部强制 ====================

    @Test
    void searchThreadsCannotAskAClaimSoTheyDoNotDenyOnIt() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.OAK_LOG.defaultBlockState());
        TerritoryClaims everywhere = (action, facts) -> true;
        Gate gate = new Gate(null, Mode.ASK, RuleSet.factory(), new PlacedBlocks(), everywhere, List.of());
        // 主线程上的拒绝(带着"a land claim forbids it")由 GameTest 在活世界里钉
        assertTrue(gate.judge(Action.breakBlock(POS, world.getBlockState(POS)), world).allowed(),
                "搜索线程问不到领地:按不知道放行,执行时再拦");
    }

    // ==================== 模式 ====================

    @Test
    void bypassAllowsEverythingAndObserveDeniesEverything() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.CHEST.defaultBlockState());
        PlacedBlocks placed = new PlacedBlocks();
        placed.record(POS);
        Action dig = Action.breakBlock(POS, world.getBlockState(POS));

        assertTrue(gate(Mode.BYPASS, RuleSet.factory(), placed).judge(dig, world).allowed(), "bypass 全放");
        Verdict observed = gate(Mode.OBSERVE, RuleSet.factory(), placed).judge(dig, world);
        assertEquals(Verdict.Kind.DENY, observed.kind());
        assertTrue(observed.reason().contains("observe mode"));
        // observe 连自然方块也不动
        world.set(POS.north(), Blocks.DIRT.defaultBlockState());
        assertFalse(gate(Mode.OBSERVE, RuleSet.factory(), placed)
                .judge(Action.breakBlock(POS.north(), world.getBlockState(POS.north())), world).allowed());
    }

    // ==================== 任务期授权 ====================

    @Test
    void aGrantCoversTheSameRuleOnTheSameKindOnly() {
        FakeWorld world = new FakeWorld();
        PlacedBlocks placed = new PlacedBlocks();
        BlockPos first = POS;
        BlockPos second = POS.offset(4, 0, 0);
        BlockPos stone = POS.offset(8, 0, 0);
        for (BlockPos p : List.of(first, second)) {
            world.set(p, Blocks.OAK_LOG.defaultBlockState());
            placed.record(p);
        }
        world.set(stone, Blocks.STONE.defaultBlockState());
        placed.record(stone);

        Gate ungranted = gate(Mode.ASK, RuleSet.factory(), placed);
        Action digFirst = Action.breakBlock(first, world.getBlockState(first));
        ConsentItem grant = ConsentItem.of(digFirst, ungranted.judge(digFirst, world));

        Gate granted = new Gate(null, Mode.ASK, RuleSet.factory(), placed, TerritoryClaims.NONE, List.of(grant));
        assertTrue(granted.judge(digFirst, world).allowed());
        assertTrue(granted.judge(Action.breakBlock(second, world.getBlockState(second)), world).allowed(),
                "同一行规则问出来的同一种方块:挖一堆只问一次");
        assertTrue(granted.judge(Action.breakBlock(stone, world.getBlockState(stone)), world).asks(),
                "换一种方块另问");
        assertEquals(Verdict.Kind.DENY, new Gate(null, Mode.OBSERVE, RuleSet.factory(), placed, TerritoryClaims.NONE,
                List.of(grant)).judge(digFirst, world).kind(), "授权只把问变成放行,解不开拒绝");
    }
}
