package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.FakeWorld;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.RuleSet;
import com.dwinovo.numen.permission.Verb;
import com.dwinovo.numen.permission.Verdict;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 游戏适配层的裁决:出厂层与硬规则对着真实的方块与放置记录读出归属({@link Whose}),是谁的就怎么办——没人的随便挖,自己的
 * 随便拆,主人的、说不清的要问,别人的碰不得;三种模式;任务期授权按"同一行规则 + 同一种东西"放行;记住的规则怎么推。
 * 判定顺序本身在规则核心的单测里({@code PolicyTest}),这里钉的是它接上游戏之后的样子。需要 MC 注册表。
 */
@Tag("mc")
class GateTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID HER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID SISTER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final UUID STRANGER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final UUID RIVAL_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final UUID RIVAL_COMPANION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    /** 要动手的是她:名字 Aria,主人 Steve。 */
    private static final Actor HER = new Actor(HER_ID, "Aria", OWNER_ID);
    private static final PlacedBlocks.Placer OWNERS = new PlacedBlocks.Placer(OWNER_ID, "Steve");
    private static final PlacedBlocks.Placer HERS = new PlacedBlocks.Placer(HER_ID, "Aria", OWNER_ID);
    private static final PlacedBlocks.Placer SISTERS = new PlacedBlocks.Placer(SISTER_ID, "Bea", OWNER_ID);
    private static final PlacedBlocks.Placer STRANGERS = new PlacedBlocks.Placer(STRANGER_ID, "Alex");
    private static final PlacedBlocks.Placer RIVALS = new PlacedBlocks.Placer(RIVAL_COMPANION_ID, "Cy", RIVAL_OWNER_ID);

    private static final BlockPos POS = new BlockPos(3, 64, 3);

    private static boolean booted;

    @BeforeAll
    static void boot() {
        booted = FakeWorld.boot();
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过裁决钉桩");
        // 标签内容来自数据包,无头引导下手动绑:这几样是"地形自己长的"
        bindBlockTags(Map.of(
                NaturalBlocks.TAG, holders(Blocks.STONE, Blocks.DIRT, Blocks.OAK_LOG, Blocks.OAK_LEAVES),
                BlockTags.OVERWORLD_NATURAL_LOGS, holders(Blocks.OAK_LOG),
                BlockTags.LOGS, holders(Blocks.OAK_LOG, Blocks.STRIPPED_OAK_LOG)));
    }

    @AfterEach
    void tearDown() {
        if (booted) {
            bindBlockTags(Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    private static void bindBlockTags(Map<TagKey<Block>, List<Holder<Block>>> tags) {
        ((MappedRegistry<Block>) BuiltInRegistries.BLOCK).bindTags(tags);
    }

    private static List<Holder<Block>> holders(Block... blocks) {
        return java.util.Arrays.stream(blocks).map(b -> (Holder<Block>) BuiltInRegistries.BLOCK.wrapAsHolder(b)).toList();
    }

    private static Gate gate(PlacedBlocks placed) {
        return gate(Mode.ASK, RuleSet.EMPTY, FactoryRules.layer(), placed, Structures.NONE, false);
    }

    private static Gate gate(Mode mode, RuleSet owner, RuleSet factory, PlacedBlocks placed, Structures structures,
                             boolean unknownAsOther) {
        return new Gate(HER, Layers.policy(mode, FactoryRules.hard(), owner, factory), placed, structures, unknownAsOther, List.of(), Hits.NONE);
    }

    private static RuleSet rules(List<String> deny, List<String> ask, List<String> allow) {
        return new RuleSet(deny.stream().map(Groups::parse).toList(),
                ask.stream().map(Groups::parse).toList(),
                allow.stream().map(Groups::parse).toList());
    }

    private static Action dig(FakeWorld world, BlockPos pos) {
        return Action.breakBlock(pos, world.getBlockState(pos));
    }

    // ==================== 出厂表 ====================

    @Test
    void factoryAllowsNatureAndAsksForTheOwnersThingsAndTheUnsaid() {
        FakeWorld world = new FakeWorld();
        PlacedBlocks placed = new PlacedBlocks();
        Gate gate = gate(placed);

        world.set(POS, Blocks.STONE.defaultBlockState());
        assertTrue(gate.judge(dig(world, POS), world).allowed(), "没人的石头由出厂 allow 行放行");

        BlockPos cobble = POS.east();
        world.set(cobble, Blocks.COBBLESTONE.defaultBlockState());
        placed.record(cobble, OWNERS);
        Verdict owners = gate.judge(dig(world, cobble), world);
        assertTrue(owners.asks(), "主人放的要问");
        assertFalse(owners.allowed(), "问 ≠ 放行");
        assertEquals("break(owner)", owners.rule().toString());
        assertEquals("belongs to the owner", owners.cause());
        assertEquals(Component.empty().append(Component.translatable("numen.permission.name.owner")),
                gate.consentItem(dig(world, cobble), owners, world).shownCause(), "主人看到的理由说是你的");

        BlockPos planks = POS.west();
        world.set(planks, Blocks.OAK_PLANKS.defaultBlockState());
        Verdict unknown = gate.judge(dig(world, planks), world);
        assertTrue(unknown.asks(), "不在记录里、不在结构里、也不是地形会长出来的:说不清,问");
        assertEquals("break(unknown)", unknown.rule().toString());

        BlockPos chest = POS.north();
        world.set(chest, Blocks.CHEST.defaultBlockState());
        placed.record(chest, OWNERS);
        Verdict chestVerdict = gate.judge(dig(world, chest), world);
        assertTrue(chestVerdict.asks());
        assertTrue(gate.consentItem(dig(world, chest), chestVerdict, world).irreversible(),
                "搜索线程读不到内容按有:拆装着东西的容器撤不回");
        assertTrue(gate.judge(Action.useBlock(chest, world.getBlockState(chest)), world).allowed(), "开箱子随便");
        assertTrue(gate.judge(Action.take(chest, world.getBlockState(chest), Items.DIRT), world).allowed(),
                "从主人的容器拿东西放行");
        assertTrue(gate.judge(Action.drop(Items.DIRT), world).asks(), "丢东西要问");
        assertTrue(gate.judge(Action.place(cobble.above(), Blocks.AIR.defaultBlockState(), Items.DIRT), world).allowed(),
                "贴着主人的东西放泥土随便");
        assertTrue(gate.judge(Action.place(cobble.above(), Blocks.AIR.defaultBlockState(), Items.LAVA_BUCKET), world)
                .asks(), "贴着主人的东西放岩浆要问");
        assertTrue(gate.judge(Action.place(POS.offset(9, 0, 0), Blocks.AIR.defaultBlockState(), Items.LAVA_BUCKET), world)
                .allowed(), "远处放岩浆随便");
        assertTrue(gate.judge(Action.place(cobble.above(), Blocks.AIR.defaultBlockState(), null), world).allowed(),
                "规划期还不知道放什么:不危险那一行放行");
    }

    @Test
    void factoryTablesAreWhatTheDesignSays() {
        assertEquals(List.of("break(other)", "place(other)", "take(other)", "attack(other)", "use_entity(other)",
                "place(hazard_item & near(other))"), FactoryRules.HARD);
        assertEquals(List.of(
                "break(owner)", "break(unknown)", "break(structure(#minecraft:village))", "break(block_entity)",
                "break(self & contents)", "attack(owner)", "attack(self)", "attack(named)", "attack(villager)",
                "use_entity(owner)", "drop(*)", "place(hazard_item & near(owner))"), FactoryRules.ASK);
        assertEquals(List.of(
                "break(nature & !block_entity)", "break(self & !contents)",
                "break(structure & !structure(#minecraft:village) & !block_entity)",
                "place(!hazard_item)", "place(hazard_item & !near(owner) & !near(other))",
                "attack(hostile)", "attack(nature & !named & !villager)", "use_block(*)", "use_entity(nature)",
                "use_entity(self)", "take(owner)", "take(self)", "take(structure & !structure(#minecraft:village))",
                "command(help)", "command(list)", "command(me)", "command(msg)", "command(teammsg)",
                "command(seed)", "command(random)"), FactoryRules.ALLOW);
        assertTrue(FactoryRules.layer().deny().isEmpty(), "出厂层不写死任何拒绝;拒绝只在硬规则与主人的 deny 行");
    }

    // ==================== 谁的 ====================

    @Test
    void herOwnBlocksAreHersTheOwnersAndHisOtherCompanionsAreTheOwners() {
        FakeWorld world = new FakeWorld();
        PlacedBlocks placed = new PlacedBlocks();
        Gate gate = gate(placed);

        world.set(POS, Blocks.COBBLESTONE.defaultBlockState());
        placed.record(POS, HERS);
        assertTrue(gate.judge(dig(world, POS), world).allowed(), "她垫的圆石,拆都不问");
        BlockPos door = POS.east();
        world.set(door, Blocks.OAK_DOOR.defaultBlockState());
        placed.record(door, HERS);
        assertTrue(gate.judge(dig(world, door), world).allowed(), "她自己装的门也是她的");
        assertTrue(gate.judge(Action.place(POS, world.getBlockState(POS), Items.GLASS), world).allowed(),
                "换 = 先拆后放:放不危险的方块放行");

        // 她放的箱子装着东西(搜索线程按有):东西多半是主人的,撤不回,仍问
        BlockPos chest = POS.west();
        world.set(chest, Blocks.CHEST.defaultBlockState());
        placed.record(chest, HERS);
        Verdict chestVerdict = gate.judge(dig(world, chest), world);
        assertTrue(chestVerdict.asks(), "带方块实体的不进 allow 行");
        assertEquals("break(block_entity)", chestVerdict.rule().toString());
        assertTrue(gate.consentItem(dig(world, chest), chestVerdict, world).irreversible(), "撤不回由对象身上的事实标");

        // 主人名下别的同伴放的算主人的
        BlockPos sisters = POS.north();
        world.set(sisters, Blocks.COBBLESTONE.defaultBlockState());
        placed.record(sisters, SISTERS);
        Verdict sister = gate.judge(dig(world, sisters), world);
        assertTrue(sister.asks());
        assertEquals("break(owner)", sister.rule().toString());

        // 同一格换一个要动手的人:另一只同伴拆她的,是主人的
        world.set(POS.south(), Blocks.COBBLESTONE.defaultBlockState());
        placed.record(POS.south(), HERS);
        Gate bea = new Gate(new Actor(SISTER_ID, "Bea", OWNER_ID), Layers.policy(Mode.ASK, FactoryRules.hard(), RuleSet.EMPTY, FactoryRules.layer()), placed, Structures.NONE, false, List.of(), Hits.NONE);
        assertEquals("break(owner)", bea.judge(dig(world, POS.south()), world).rule().toString());
    }

    @Test
    void whatOtherPlayersPlacedIsBeyondRulesAndModes() {
        FakeWorld world = new FakeWorld();
        PlacedBlocks placed = new PlacedBlocks();
        world.set(POS, Blocks.COBBLESTONE.defaultBlockState());
        placed.record(POS, STRANGERS);
        BlockPos rivals = POS.east();
        world.set(rivals, Blocks.COBBLESTONE.defaultBlockState());
        placed.record(rivals, RIVALS);

        for (BlockPos p : List.of(POS, rivals)) {
            Verdict v = gate(placed).judge(dig(world, p), world);
            assertEquals(Verdict.Kind.DENY, v.kind(), "别人的(含别人名下同伴放的)碰不得");
            assertTrue(v.reason().contains("hard rule break(other)"), v.reason());
            assertEquals(Verdict.Kind.DENY, gate(Mode.BYPASS, RuleSet.EMPTY, FactoryRules.layer(), placed,
                    Structures.NONE, false).judge(dig(world, p), world).kind(), "bypass 解不开");
            assertEquals(Verdict.Kind.DENY, gate(Mode.ASK, rules(List.of(), List.of(), List.of("*(*)")),
                    FactoryRules.layer(), placed, Structures.NONE, false).judge(dig(world, p), world).kind(),
                    "主人写的 allow 解不开");
        }
        assertEquals(Verdict.Kind.DENY, gate(placed).judge(
                Action.place(POS, world.getBlockState(POS), Items.DIRT), world).kind(), "盖掉别人的方块也不行");
        assertTrue(gate(placed).judge(Action.place(POS.above(), Blocks.AIR.defaultBlockState(), Items.LAVA_BUCKET), world)
                .reason().contains("hard rule"), "别人的东西旁边放岩浆也是硬规则");

        // 服主关了硬规则:没有哪一行说到别人的,问
        Gate noHard = new Gate(HER, Layers.policy(Mode.ASK, List.of(), RuleSet.EMPTY, FactoryRules.layer()), placed, Structures.NONE, false, List.of(), Hits.NONE);
        assertTrue(noHard.judge(dig(world, POS), world).asks());
    }

    @Test
    void unknownBlocksAskAndOnADedicatedServerAreSomeoneElses() {
        FakeWorld world = new FakeWorld();
        PlacedBlocks placed = new PlacedBlocks();
        world.set(POS, Blocks.OAK_PLANKS.defaultBlockState());
        assertTrue(gate(placed).judge(dig(world, POS), world).asks());
        Gate dedicated = gate(Mode.ASK, RuleSet.EMPTY, FactoryRules.layer(), placed, Structures.NONE, true);
        Verdict v = dedicated.judge(dig(world, POS), world);
        assertEquals(Verdict.Kind.DENY, v.kind(), "专用服上说不清的按别人的算");
        assertTrue(v.reason().contains("hard rule break(other)"));

        // 记下放的人之前的存档:有人放过,不当成地形
        world.set(POS.east(), Blocks.STONE.defaultBlockState());
        placed.record(POS.east(), PlacedBlocks.Placer.UNKNOWN);
        Verdict old = gate(placed).judge(dig(world, POS.east()), world);
        assertTrue(old.asks());
        assertEquals("break(unknown)", old.rule().toString());
    }

    @Test
    void theWildAndTheCabinAreToldApartByTheirLeaves() {
        FakeWorld world = new FakeWorld();
        Gate gate = gate(new PlacedBlocks());

        // 野树:原木贴着不持久的树叶
        world.set(POS, Blocks.OAK_LOG.defaultBlockState());
        world.set(POS.above(), Blocks.OAK_LOG.defaultBlockState());
        world.set(POS.above(2), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false));
        assertTrue(gate.judge(dig(world, POS), world).allowed(), "树干底下那根也是野树");
        assertTrue(gate.judge(dig(world, POS.above(2)), world).allowed(), "不持久的树叶是自然的");

        // 木屋:原木连着的是持久树叶,树叶本身也是玩家放的
        BlockPos cabin = POS.offset(8, 0, 0);
        world.set(cabin, Blocks.OAK_LOG.defaultBlockState());
        world.set(cabin.above(), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        Verdict log = gate.judge(dig(world, cabin), world);
        assertTrue(log.asks(), "连着持久树叶的原木不是野树");
        assertEquals("break(unknown)", log.rule().toString());
        assertTrue(gate.judge(dig(world, cabin.above()), world).asks(), "持久的树叶不是自然的");

        // 光秃秃的一根原木:不是野树
        BlockPos bare = POS.offset(0, 0, 8);
        world.set(bare, Blocks.OAK_LOG.defaultBlockState());
        assertTrue(gate.judge(dig(world, bare), world).asks());
    }

    @Test
    void aGeneratedStructureComesBeforeNature() {
        FakeWorld world = new FakeWorld();
        PlacedBlocks placed = new PlacedBlocks();
        Structures structures = new Structures(List.of(new Structures.Piece(
                new BoundingBox(0, 60, 0, 10, 70, 10), Holder.direct(null))));
        Gate gate = gate(Mode.ASK, RuleSet.EMPTY, FactoryRules.layer(), placed, structures, false);

        world.set(POS, Blocks.OAK_PLANKS.defaultBlockState());
        assertTrue(gate.judge(dig(world, POS), world).allowed(), "废弃结构里的木板直接挖");
        BlockPos chest = POS.east();
        world.set(chest, Blocks.CHEST.defaultBlockState());
        assertEquals("break(block_entity)", gate.judge(dig(world, chest), world).rule().toString(),
                "结构里的战利品箱:带方块实体的不进 allow 行,问");
        assertTrue(gate.judge(Action.take(chest, world.getBlockState(chest), Items.DIRT), world).allowed(),
                "废弃结构里的战利品箱随便拿");
        world.set(POS.west(), Blocks.OAK_PLANKS.defaultBlockState());
        placed.record(POS.west(), OWNERS);
        assertEquals("break(owner)", gate.judge(dig(world, POS.west()), world).rule().toString(),
                "主人在结构里放的方块是主人的,不是结构的");
        world.set(POS.offset(20, 0, 0), Blocks.OAK_PLANKS.defaultBlockState());
        assertEquals("break(unknown)", gate.judge(dig(world, POS.offset(20, 0, 0)), world).rule().toString(),
                "结构件范围之外照旧");
    }

    // ==================== 分层 ====================

    @Test
    void anOwnersAskRowBeatsTheFactoryAllowRow() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.STONE.defaultBlockState());
        Gate gate = gate(Mode.ASK, rules(List.of(), List.of("break(nature & !block_entity)"), List.of()),
                FactoryRules.layer(), new PlacedBlocks(), Structures.NONE, false);
        Verdict v = gate.judge(dig(world, POS), world);
        assertTrue(v.asks(), "主人写了挖没人的也要问,出厂的 allow 行压不过它");
        assertEquals("break(nature & !block_entity)", v.rule().toString());
    }

    @Test
    void aRememberedAllowBeatsTheFactoryAskItWasCarvedFrom() {
        FakeWorld world = new FakeWorld();
        PlacedBlocks placed = new PlacedBlocks();
        BlockPos cobble = POS;
        BlockPos log = POS.offset(3, 0, 0);
        world.set(cobble, Blocks.COBBLESTONE.defaultBlockState());
        world.set(log, Blocks.OAK_PLANKS.defaultBlockState());
        placed.record(cobble, OWNERS);
        placed.record(log, OWNERS);
        Gate gate = gate(Mode.ASK, rules(List.of(), List.of(), List.of("break(owner & minecraft:cobblestone)")),
                FactoryRules.layer(), placed, Structures.NONE, false);

        assertTrue(gate.judge(dig(world, cobble), world).allowed(), "记住的圆石:主人放的也不再问");
        Verdict planks = gate.judge(dig(world, log), world);
        assertTrue(planks.asks(), "主人放的木板没被记住,照旧问");
        assertEquals("break(owner)", planks.rule().toString());
    }

    @Test
    void theOwnersDenyRowBeatsTheFactoryAllowRowAndTheOtherWayRoundForAllow() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.DIRT.defaultBlockState());
        PlacedBlocks placed = new PlacedBlocks();
        Gate denied = gate(Mode.ASK, rules(List.of("break(minecraft:dirt)"), List.of(), List.of()),
                FactoryRules.layer(), placed, Structures.NONE, false);
        assertEquals(Verdict.Kind.DENY, denied.judge(dig(world, POS), world).kind(), "主人的 deny 行压过出厂 allow 行");

        placed.record(POS, OWNERS);
        Gate allowed = gate(Mode.ASK, rules(List.of(), List.of(), List.of("break(owner)")),
                FactoryRules.layer(), placed, Structures.NONE, false);
        assertTrue(allowed.judge(dig(world, POS), world).allowed(), "主人的 allow 行压过出厂 ask 行");
    }

    @Test
    void nothingMatchedMeansAsk() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.STONE.defaultBlockState());
        Gate gate = gate(Mode.ASK, RuleSet.EMPTY, RuleSet.EMPTY, new PlacedBlocks(), Structures.NONE, false);
        Verdict v = gate.judge(dig(world, POS), world);
        assertTrue(v.asks(), "没有一行规则说过的事去问主人,不放行");
        assertNull(v.rule());
        assertEquals(Verdict.UNCOVERED, v.cause());
    }

    // ==================== 记住的规则 ====================

    @Test
    void rememberingKeepsTheAskedConditionsAndPinsTheKind() {
        FakeWorld world = new FakeWorld();
        PlacedBlocks placed = new PlacedBlocks();
        world.set(POS, Blocks.COBBLESTONE.defaultBlockState());
        placed.record(POS, OWNERS);
        Gate gate = gate(placed);
        Action dig = dig(world, POS);
        ConsentItem item = gate.consentItem(dig, gate.judge(dig, world), world);
        assertEquals("break(owner & minecraft:cobblestone)", item.remember().toString());
        assertTrue(item.remember().matches(new ActView(dig, new Facts(world, placed, null, HER, Structures.NONE, false))),
                "记下的规则盖得住这次问的动作");

        // 主人自己写的 ask 行带取反项:原样留着
        world.set(POS.north(), Blocks.STONE.defaultBlockState());
        Gate strict = gate(Mode.ASK, rules(List.of(), List.of("break(!owner & !block_entity)"), List.of()),
                FactoryRules.layer(), placed, Structures.NONE, false);
        Action digStone = dig(world, POS.north());
        assertEquals("break(!owner & !block_entity & minecraft:stone)",
                strict.consentItem(digStone, strict.judge(digStone, world), world).remember().toString());

        // 没有任何一行覆盖:只钉对象
        Gate bare = gate(Mode.ASK, RuleSet.EMPTY, RuleSet.EMPTY, placed, Structures.NONE, false);
        Action drop = Action.drop(Items.DIAMOND);
        assertEquals("drop(minecraft:diamond)",
                bare.consentItem(drop, bare.judge(drop, world), world).remember().toString());

        // 说不清的木板:记的是这一种
        world.set(POS.south(), Blocks.OAK_PLANKS.defaultBlockState());
        Action planks = dig(world, POS.south());
        assertEquals("break(unknown & minecraft:oak_planks)",
                gate.consentItem(planks, gate.judge(planks, world), world).remember().toString());
    }

    @Test
    void aRememberedRowLetsTheSameKindThroughNextTime() {
        FakeWorld world = new FakeWorld();
        PlacedBlocks placed = new PlacedBlocks();
        BlockPos first = POS;
        BlockPos second = POS.offset(5, 0, 0);
        for (BlockPos p : List.of(first, second)) {
            world.set(p, Blocks.COBBLESTONE.defaultBlockState());
            placed.record(p, OWNERS);
        }
        Gate before = gate(placed);
        Action digFirst = dig(world, first);
        ConsentItem asked = before.consentItem(digFirst, before.judge(digFirst, world), world);

        List<Rule> remembered = ConsentItem.remembered(List.of(asked, asked));
        assertEquals(1, remembered.size(), "同一行只记一次");
        Gate after = gate(Mode.ASK, new RuleSet(List.of(), List.of(), remembered), FactoryRules.layer(), placed,
                Structures.NONE, false);
        assertTrue(after.judge(dig(world, second), world).allowed(), "记住之后同一种东西不再问,也不靠任务期授权");
    }

    // ==================== 模式 ====================

    @Test
    void bypassAllowsEverythingButHardRulesAndObserveDeniesEverything() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.CHEST.defaultBlockState());
        PlacedBlocks placed = new PlacedBlocks();
        placed.record(POS, OWNERS);
        Action dig = dig(world, POS);

        assertTrue(gate(Mode.BYPASS, RuleSet.EMPTY, FactoryRules.layer(), placed, Structures.NONE, false)
                .judge(dig, world).allowed(), "bypass 全放");
        Verdict observed = gate(Mode.OBSERVE, RuleSet.EMPTY, FactoryRules.layer(), placed, Structures.NONE, false)
                .judge(dig, world);
        assertEquals(Verdict.Kind.DENY, observed.kind());
        assertTrue(observed.reason().contains("observe mode"));
        // observe 连自然方块也不动
        world.set(POS.north(), Blocks.DIRT.defaultBlockState());
        assertFalse(gate(Mode.OBSERVE, RuleSet.EMPTY, FactoryRules.layer(), placed, Structures.NONE, false)
                .judge(dig(world, POS.north()), world).allowed());
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
            placed.record(p, OWNERS);
        }
        world.set(stone, Blocks.STONE.defaultBlockState());
        placed.record(stone, OWNERS);

        Gate ungranted = gate(placed);
        Action digFirst = dig(world, first);
        ConsentItem grant = ungranted.consentItem(digFirst, ungranted.judge(digFirst, world), world);

        Gate granted = new Gate(HER, Layers.policy(Mode.ASK, FactoryRules.hard(), RuleSet.EMPTY, FactoryRules.layer()), placed, Structures.NONE, false, List.of(grant), Hits.NONE);
        assertTrue(granted.judge(digFirst, world).allowed());
        assertTrue(granted.judge(dig(world, second), world).allowed(), "同一行规则问出来的同一种方块:挖一堆只问一次");
        assertTrue(granted.judge(dig(world, stone), world).asks(), "换一种方块另问");
        assertEquals(Verdict.Kind.DENY, new Gate(HER, Layers.policy(Mode.OBSERVE, FactoryRules.hard(), RuleSet.EMPTY, FactoryRules.layer()), placed, Structures.NONE, false, List.of(grant), Hits.NONE).judge(digFirst, world).kind(),
                "授权只把问变成放行,解不开拒绝");
    }

    @Test
    void planningNeverCountsAHitOnlyTheLiveQuestionDoes() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.STONE.defaultBlockState());
        Gate planner = new Gate(HER, Layers.policy(Mode.ASK, FactoryRules.hard(), RuleSet.EMPTY, FactoryRules.layer()),
                new PlacedBlocks(), Structures.NONE, false, List.of(), (layer, table, rule) -> {
                    throw new AssertionError("pricing a path counted a hit on " + rule);
                });
        assertTrue(planner.judge(dig(world, POS), world).allowed());
        assertEquals(0, FactoryRules.layer().allow().indexOf(planner.judge(dig(world, POS), world).rule()),
                "逐格定价读得到是哪一行放的,只是不记账");
        assertEquals(Layers.FACTORY, planner.judge(dig(world, POS), world).layer());
    }

    @Test
    void whatCannotBeUndoneIsRegisteredPerVerb() {
        assertTrue(Groups.VOCABULARY.get("owner").irreversibleOn().contains(Verb.ATTACK), "打死主人的宠物撤不回");
        assertTrue(Groups.VOCABULARY.get("self").irreversibleOn().contains(Verb.ATTACK));
        assertTrue(Groups.VOCABULARY.get("other").irreversibleOn().contains(Verb.ATTACK));
        assertTrue(Groups.VOCABULARY.get("contents").irreversibleOn().contains(Verb.BREAK), "拆装着东西的容器撤不回");
        assertTrue(Groups.VOCABULARY.get("named").irreversibleOn().isEmpty(), "打有名字的不算撤不回");
        assertFalse(Groups.VOCABULARY.get("owner").irreversibleOn().contains(Verb.BREAK), "拆主人的墙撤得回(放回去)");
    }
}
