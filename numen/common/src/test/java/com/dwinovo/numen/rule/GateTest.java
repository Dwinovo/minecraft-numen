package com.dwinovo.numen.rule;

import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.api.permission.ActView;
import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.permission.Actor;
import com.dwinovo.numen.api.permission.ConsentItem;
import com.dwinovo.numen.api.permission.FactoryRules;
import com.dwinovo.numen.api.permission.Facts;
import com.dwinovo.numen.api.permission.Gate;
import com.dwinovo.numen.api.permission.Groups;
import com.dwinovo.numen.api.permission.Hits;
import com.dwinovo.numen.api.permission.Layers;
import com.dwinovo.numen.api.permission.Owners;
import com.dwinovo.numen.api.permission.Structures;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.RuleSet;
import com.dwinovo.numen.permission.Verb;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.permission.Vocabulary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.FluidState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 游戏适配层的裁决:出厂层与硬规则对着真实的方块与一份归属查表读出是谁的,是谁的就怎么办——没主人的随便挖,自己的
 * 随便拆,主人的要问,别人的碰不得;三种模式;任务期授权按"同一行规则 + 同一种东西"放行;记住的规则怎么推。
 * 判定顺序本身在规则核心的单测里({@code PolicyTest}),这里钉的是它接上游戏之后的样子。出厂规则写的词有 Numen 登记的那几个
 * ({@link VanillaWords}),所以这份钉桩住在 Numen 这一侧。需要 MC 注册表。
 *
 * <p>方块是谁的由测试直接给:一张"格子 → 那个人"的表,和方块归属的回答者交回的查表是同一种东西。没写进表的格没人说,就是没主人的。
 */
@Tag("mc")
class GateTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID HER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID SISTER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final UUID STRANGER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final UUID RIVAL_COMPANION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    /** 要动手的是她:名字 Aria,主人 Steve,主人名下还有一只 Bea。 */
    private static final Actor HER = new Actor(HER_ID, "Aria", OWNER_ID, Set.of(SISTER_ID));

    private static final BlockPos POS = new BlockPos(3, 64, 3);

    @BeforeAll
    static void boot() {
        NumenApiFixture.install();
    }

    /** Map 后备的方块视图:没摆的格是空气,读不到方块实体(搜索线程那一种)。 */
    private static final class FakeWorld implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        FakeWorld set(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
            return this;
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    /** 一份归属:{@code cells} 里写着哪一格是谁的,此后往里加的也读得到。 */
    private static Owners.Blocks owned(Map<BlockPos, UUID> cells) {
        return Owners.Blocks.of(List.of(cells::get));
    }

    private static Gate gate(Map<BlockPos, UUID> cells) {
        return gate(Mode.ASK, RuleSet.EMPTY, FactoryRules.layer(), cells, Structures.NONE);
    }

    private static Gate gate(Mode mode, RuleSet owner, RuleSet factory, Map<BlockPos, UUID> cells, Structures structures) {
        return gate(HER, Layers.policy(mode, FactoryRules.hard(), owner, factory), cells, structures);
    }

    private static Gate gate(Actor actor, com.dwinovo.numen.permission.Policy policy, Map<BlockPos, UUID> cells,
                             Structures structures) {
        return new Gate(actor, policy, structures, owned(cells), List.of(), Hits.NONE);
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
    void factoryAllowsWhatIsNobodysAndAsksForTheOwnersThings() {
        FakeWorld world = new FakeWorld();
        Map<BlockPos, UUID> cells = new HashMap<>();
        Gate gate = gate(cells);

        world.set(POS, Blocks.STONE.defaultBlockState());
        assertTrue(gate.judge(dig(world, POS), world).allowed(), "没主人的石头由出厂 allow 行放行");

        BlockPos cobble = POS.east();
        world.set(cobble, Blocks.COBBLESTONE.defaultBlockState());
        cells.put(cobble, OWNER_ID);
        Verdict owners = gate.judge(dig(world, cobble), world);
        assertTrue(owners.asks(), "主人的要问");
        assertFalse(owners.allowed(), "问 ≠ 放行");
        assertEquals("break(owner)", owners.rule().toString());
        assertEquals("belongs to the owner", owners.cause());
        assertEquals(Component.empty().append(Component.translatable("numen.permission.name.owner")),
                gate.consentItem(dig(world, cobble), owners, world).shownCause(), "主人看到的理由说是你的");

        BlockPos planks = POS.west();
        world.set(planks, Blocks.OAK_PLANKS.defaultBlockState());
        Verdict nobodys = gate.judge(dig(world, planks), world);
        assertTrue(nobodys.allowed(), "没人说是谁的木板:没主人的,直接挖");
        assertEquals("break(nature & !block_entity & !structure(#minecraft:village))", nobodys.rule().toString());

        BlockPos chest = POS.north();
        world.set(chest, Blocks.CHEST.defaultBlockState());
        cells.put(chest, OWNER_ID);
        Verdict chestVerdict = gate.judge(dig(world, chest), world);
        assertTrue(chestVerdict.asks());
        assertTrue(gate.consentItem(dig(world, chest), chestVerdict, world).irreversible(),
                "搜索线程读不到内容按有:拆装着东西的容器撤不回");
        Verdict open = gate.judge(Action.useBlock(chest, world.getBlockState(chest)), world);
        assertTrue(open.allowed(), "开主人装着东西的箱子(搜索线程读不到内容按有)放行,往外拿不另问");
        assertEquals("use_block(contents & !other & !structure(#minecraft:village))", open.rule().toString());
        BlockPos door = POS.south();
        world.set(door, Blocks.OAK_DOOR.defaultBlockState());
        assertEquals("use_block(!contents)", gate.judge(Action.useBlock(door, world.getBlockState(door)), world)
                .rule().toString(), "里面没装东西的方块右键放行");
        BlockPos barrel = POS.south(2);
        world.set(barrel, Blocks.BARREL.defaultBlockState());
        Action openBarrel = Action.useBlock(barrel, world.getBlockState(barrel));
        assertEquals("use_block(contents & !other & !structure(#minecraft:village))",
                gate.judge(openBarrel, world).rule().toString(), "没主人的、装着东西的容器也放行");
        Gate unruled = gate(Mode.ASK, RuleSet.EMPTY, RuleSet.EMPTY, cells, Structures.NONE);
        Verdict unsaid = unruled.judge(openBarrel, world);
        assertTrue(unsaid.asks(), "没有一行说到的右键:问");
        assertNull(unsaid.rule());
        assertTrue(unruled.consentItem(openBarrel, unsaid, world).irreversible(), "动容器里的东西撤不回");
        assertTrue(gate.judge(Action.drop(Items.DIRT), world).asks(), "丢东西要问");
        assertTrue(gate.judge(Action.place(cobble.above(), Blocks.AIR.defaultBlockState(), Items.DIRT), world).allowed(),
                "贴着主人的东西放泥土随便");
        assertTrue(gate.judge(Action.place(cobble.above(), Blocks.AIR.defaultBlockState(), Items.LAVA_BUCKET), world)
                .asks(), "贴着主人的东西放岩浆要问");
        assertTrue(gate.judge(Action.place(POS.offset(9, 0, 0), Blocks.AIR.defaultBlockState(), Items.LAVA_BUCKET), world)
                .allowed(), "远处放岩浆随便");
        Verdict tnt = gate.judge(Action.place(POS.offset(9, 0, 0), Blocks.AIR.defaultBlockState(), Items.TNT), world);
        assertTrue(tnt.allowed(), "远处放 TNT 也随便");
        assertEquals("place(hazard_item & !near(owner) & !near(other))", tnt.rule().toString(), "TNT 是危险物品");
        assertTrue(gate.judge(Action.place(cobble.above(), Blocks.AIR.defaultBlockState(), null), world).allowed(),
                "规划期还不知道放什么:不危险那一行放行");
    }

    @Test
    void factoryTablesAreWhatTheDesignSays() {
        assertEquals(List.of("break(other)", "place(other)", "use_block(other & contents)", "attack(other)",
                "use_entity(other)", "place(hazard_item & near(other))"), FactoryRules.HARD);
        assertEquals(List.of(
                "break(owner)", "break(structure(#minecraft:village))", "break(block_entity)",
                "break(self & contents)", "attack(owner)", "attack(self)", "attack(named)", "attack(villager)",
                "use_entity(owner)", "drop(*)", "place(hazard_item & near(owner))"), FactoryRules.ASK);
        assertEquals(List.of(
                "break(nature & !block_entity & !structure(#minecraft:village))", "break(self & !contents)",
                "place(!hazard_item)", "place(hazard_item & !near(owner) & !near(other))",
                "attack(hostile)", "attack(nature & !named & !villager)", "use_block(!contents)",
                "use_block(contents & !other & !structure(#minecraft:village))", "use_entity(nature)",
                "use_entity(self)", "command(help)", "command(list)", "command(me)", "command(msg)", "command(teammsg)",
                "command(seed)", "command(random)"), FactoryRules.ALLOW);
        assertTrue(FactoryRules.layer().deny().isEmpty(), "出厂层不写死任何拒绝;拒绝只在硬规则与主人的 deny 行");
        assertEquals(FactoryRules.HARD, FactoryRules.hard().stream().map(Rule::toString).toList(), "每一行都解析得了");
        assertEquals(FactoryRules.ASK, FactoryRules.layer().ask().stream().map(Rule::toString).toList());
        assertEquals(FactoryRules.ALLOW, FactoryRules.layer().allow().stream().map(Rule::toString).toList());
    }

    // ==================== 谁的 ====================

    @Test
    void herOwnBlocksAreHersTheOwnersAndHisOtherCompanionsAreTheOwners() {
        FakeWorld world = new FakeWorld();
        Map<BlockPos, UUID> cells = new HashMap<>();
        Gate gate = gate(cells);

        world.set(POS, Blocks.COBBLESTONE.defaultBlockState());
        cells.put(POS, HER_ID);
        assertEquals("break(self & !contents)", gate.judge(dig(world, POS), world).rule().toString(),
                "她自己的圆石,拆都不问");
        BlockPos door = POS.east();
        world.set(door, Blocks.OAK_DOOR.defaultBlockState());
        cells.put(door, HER_ID);
        assertTrue(gate.judge(dig(world, door), world).allowed(), "她自己的门也是她的");
        assertTrue(gate.judge(Action.place(POS, world.getBlockState(POS), Items.GLASS), world).allowed(),
                "换 = 先拆后放:放不危险的方块放行");

        // 她的箱子装着东西(搜索线程按有):东西多半是主人的,撤不回,仍问
        BlockPos chest = POS.west();
        world.set(chest, Blocks.CHEST.defaultBlockState());
        cells.put(chest, HER_ID);
        Verdict chestVerdict = gate.judge(dig(world, chest), world);
        assertTrue(chestVerdict.asks(), "带方块实体的不进 allow 行");
        assertEquals("break(block_entity)", chestVerdict.rule().toString());
        assertTrue(gate.consentItem(dig(world, chest), chestVerdict, world).irreversible(), "撤不回由对象身上的事实标");

        // 主人名下别的同伴的算主人的
        BlockPos sisters = POS.north();
        world.set(sisters, Blocks.COBBLESTONE.defaultBlockState());
        cells.put(sisters, SISTER_ID);
        Verdict sister = gate.judge(dig(world, sisters), world);
        assertTrue(sister.asks());
        assertEquals("break(owner)", sister.rule().toString());

        // 同一格换一个要动手的人:另一只同伴拆她的,是主人的
        world.set(POS.south(), Blocks.COBBLESTONE.defaultBlockState());
        cells.put(POS.south(), HER_ID);
        Gate bea = gate(new Actor(SISTER_ID, "Bea", OWNER_ID, Set.of(HER_ID)),
                Layers.policy(Mode.ASK, FactoryRules.hard(), RuleSet.EMPTY, FactoryRules.layer()), cells, Structures.NONE);
        assertEquals("break(owner)", bea.judge(dig(world, POS.south()), world).rule().toString());
    }

    @Test
    void whatIsSomeoneElsesIsBeyondRulesAndModes() {
        FakeWorld world = new FakeWorld();
        Map<BlockPos, UUID> cells = new HashMap<>();
        world.set(POS, Blocks.COBBLESTONE.defaultBlockState());
        cells.put(POS, STRANGER_ID);
        BlockPos rivals = POS.east();
        world.set(rivals, Blocks.COBBLESTONE.defaultBlockState());
        cells.put(rivals, RIVAL_COMPANION_ID);

        for (BlockPos p : List.of(POS, rivals)) {
            Verdict v = gate(cells).judge(dig(world, p), world);
            assertEquals(Verdict.Kind.DENY, v.kind(), "别人的(含别人名下同伴的)碰不得");
            assertTrue(v.reason().contains("hard rule break(other)"), v.reason());
            assertEquals(Verdict.Kind.DENY, gate(Mode.BYPASS, RuleSet.EMPTY, FactoryRules.layer(), cells,
                    Structures.NONE).judge(dig(world, p), world).kind(), "bypass 解不开");
            assertEquals(Verdict.Kind.DENY, gate(Mode.ASK, rules(List.of(), List.of(), List.of("*(*)")),
                    FactoryRules.layer(), cells, Structures.NONE).judge(dig(world, p), world).kind(),
                    "主人写的 allow 解不开");
        }
        assertEquals(Verdict.Kind.DENY, gate(cells).judge(
                Action.place(POS, world.getBlockState(POS), Items.DIRT), world).kind(), "盖掉别人的方块也不行");
        assertTrue(gate(cells).judge(Action.place(POS.above(), Blocks.AIR.defaultBlockState(), Items.LAVA_BUCKET), world)
                .reason().contains("hard rule"), "别人的东西旁边放岩浆也是硬规则");
        BlockPos chest = POS.north();
        world.set(chest, Blocks.CHEST.defaultBlockState());
        cells.put(chest, STRANGER_ID);
        Verdict open = gate(cells).judge(Action.useBlock(chest, world.getBlockState(chest)), world);
        assertEquals(Verdict.Kind.DENY, open.kind(), "别人装着东西的箱子打不开:打开就是动里面的东西");
        assertTrue(open.reason().contains("hard rule use_block(other & contents)"), open.reason());
        BlockPos door = POS.south();
        world.set(door, Blocks.OAK_DOOR.defaultBlockState());
        cells.put(door, STRANGER_ID);
        assertTrue(gate(cells).judge(Action.useBlock(door, world.getBlockState(door)), world).allowed(),
                "别人的门照样开:里面没装东西");

        // 服主关了硬规则:没有哪一行说到别人的,问
        Gate noHard = gate(HER, Layers.policy(Mode.ASK, List.of(), RuleSet.EMPTY, FactoryRules.layer()), cells,
                Structures.NONE);
        assertTrue(noHard.judge(dig(world, POS), world).asks());
    }

    @Test
    void aGeneratedStructureSaysWhereACellIsNotWhoseItIs() {
        FakeWorld world = new FakeWorld();
        Map<BlockPos, UUID> cells = new HashMap<>();
        Structures structures = new Structures(List.of(new Structures.Piece(
                new BoundingBox(0, 60, 0, 10, 70, 10), Holder.direct(null))));
        Gate gate = gate(Mode.ASK, RuleSet.EMPTY, FactoryRules.layer(), cells, structures);

        world.set(POS, Blocks.OAK_PLANKS.defaultBlockState());
        assertEquals("break(nature & !block_entity & !structure(#minecraft:village))",
                gate.judge(dig(world, POS), world).rule().toString(), "废弃结构里的木板没主人,直接挖");
        BlockPos chest = POS.east();
        world.set(chest, Blocks.CHEST.defaultBlockState());
        assertEquals("break(block_entity)", gate.judge(dig(world, chest), world).rule().toString(),
                "结构里的战利品箱:带方块实体的不进 allow 行,问");
        assertEquals("use_block(contents & !other & !structure(#minecraft:village))",
                gate.judge(Action.useBlock(chest, world.getBlockState(chest)), world).rule().toString(),
                "废弃结构里的战利品箱随便开、随便拿");
        world.set(POS.west(), Blocks.OAK_PLANKS.defaultBlockState());
        cells.put(POS.west(), OWNER_ID);
        assertEquals("break(owner)", gate.judge(dig(world, POS.west()), world).rule().toString(),
                "结构里有主人的一格照样是那个人的");

        // 在不在结构里与是谁的各说各的:结构里主人的那一格两个词都成立,结构件范围之外的不在结构里
        world.set(POS.offset(20, 0, 0), Blocks.OAK_PLANKS.defaultBlockState());
        Gate strict = gate(Mode.ASK, rules(List.of("break(structure & owner)", "break(structure & nature & minecraft:oak_planks)"),
                List.of(), List.of()), FactoryRules.layer(), cells, structures);
        assertEquals("break(structure & owner)", strict.judge(dig(world, POS.west()), world).rule().toString());
        assertEquals(Verdict.Kind.DENY, strict.judge(dig(world, POS), world).kind());
        assertTrue(strict.judge(dig(world, POS.offset(20, 0, 0)), world).allowed(), "结构件范围之外的木板不在结构里");
    }

    // ==================== 分层 ====================

    @Test
    void anOwnersAskRowBeatsTheFactoryAllowRow() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.STONE.defaultBlockState());
        Gate gate = gate(Mode.ASK, rules(List.of(), List.of("break(nature & !block_entity)"), List.of()),
                FactoryRules.layer(), Map.of(), Structures.NONE);
        Verdict v = gate.judge(dig(world, POS), world);
        assertTrue(v.asks(), "主人写了挖没主人的也要问,出厂的 allow 行压不过它");
        assertEquals("break(nature & !block_entity)", v.rule().toString());
    }

    @Test
    void aRememberedAllowBeatsTheFactoryAskItWasCarvedFrom() {
        FakeWorld world = new FakeWorld();
        BlockPos cobble = POS;
        BlockPos log = POS.offset(3, 0, 0);
        world.set(cobble, Blocks.COBBLESTONE.defaultBlockState());
        world.set(log, Blocks.OAK_PLANKS.defaultBlockState());
        Map<BlockPos, UUID> cells = Map.of(cobble, OWNER_ID, log, OWNER_ID);
        Gate gate = gate(Mode.ASK, rules(List.of(), List.of(), List.of("break(owner & minecraft:cobblestone)")),
                FactoryRules.layer(), cells, Structures.NONE);

        assertTrue(gate.judge(dig(world, cobble), world).allowed(), "记住的圆石:主人的也不再问");
        Verdict planks = gate.judge(dig(world, log), world);
        assertTrue(planks.asks(), "主人的木板没被记住,照旧问");
        assertEquals("break(owner)", planks.rule().toString());
    }

    @Test
    void theOwnersDenyRowBeatsTheFactoryAllowRowAndTheOtherWayRoundForAllow() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.DIRT.defaultBlockState());
        Map<BlockPos, UUID> cells = new HashMap<>();
        Gate denied = gate(Mode.ASK, rules(List.of("break(minecraft:dirt)"), List.of(), List.of()),
                FactoryRules.layer(), cells, Structures.NONE);
        assertEquals(Verdict.Kind.DENY, denied.judge(dig(world, POS), world).kind(), "主人的 deny 行压过出厂 allow 行");

        cells.put(POS, OWNER_ID);
        Gate allowed = gate(Mode.ASK, rules(List.of(), List.of(), List.of("break(owner)")),
                FactoryRules.layer(), cells, Structures.NONE);
        assertTrue(allowed.judge(dig(world, POS), world).allowed(), "主人的 allow 行压过出厂 ask 行");
    }

    @Test
    void aRowThatNamesOneCompanionHoldsHerAndLeavesHerSistersAlone() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.STONE.defaultBlockState());
        var policy = Layers.policy(Mode.ASK, FactoryRules.hard(), rules(List.of("break(nature) @Aria"), List.of(), List.of()),
                FactoryRules.layer());
        Verdict aria = gate(HER, policy, Map.of(), Structures.NONE).judge(dig(world, POS), world);
        assertEquals(Verdict.Kind.DENY, aria.kind());
        assertTrue(aria.reason().contains("denied by rule break(nature) @Aria"), aria.reason());
        assertTrue(gate(new Actor(SISTER_ID, "Bea", OWNER_ID), policy, Map.of(), Structures.NONE)
                .judge(dig(world, POS), world).allowed(), "点名 Aria 的行管不到 Bea");
    }

    @Test
    void nothingMatchedMeansAsk() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.STONE.defaultBlockState());
        Gate gate = gate(Mode.ASK, RuleSet.EMPTY, RuleSet.EMPTY, Map.of(), Structures.NONE);
        Verdict v = gate.judge(dig(world, POS), world);
        assertTrue(v.asks(), "没有一行规则说过的事去问主人,不放行");
        assertNull(v.rule());
        assertEquals(Verdict.UNCOVERED, v.cause());
    }

    // ==================== 记住的规则 ====================

    @Test
    void rememberingKeepsTheAskedConditionsAndPinsTheKind() {
        FakeWorld world = new FakeWorld();
        Map<BlockPos, UUID> cells = new HashMap<>();
        world.set(POS, Blocks.COBBLESTONE.defaultBlockState());
        cells.put(POS, OWNER_ID);
        Gate gate = gate(cells);
        Action dig = dig(world, POS);
        ConsentItem item = gate.consentItem(dig, gate.judge(dig, world), world);
        assertEquals("break(owner & minecraft:cobblestone)", item.remember().toString());
        assertTrue(item.remember().matches(new ActView(dig, new Facts(world, null, HER, Structures.NONE, owned(cells),
                        Groups.registered()))),
                "记下的规则盖得住这次问的动作");

        // 主人自己写的 ask 行带取反项:原样留着
        world.set(POS.north(), Blocks.STONE.defaultBlockState());
        Gate strict = gate(Mode.ASK, rules(List.of(), List.of("break(!owner & !block_entity)"), List.of()),
                FactoryRules.layer(), cells, Structures.NONE);
        Action digStone = dig(world, POS.north());
        assertEquals("break(!owner & !block_entity & minecraft:stone)",
                strict.consentItem(digStone, strict.judge(digStone, world), world).remember().toString());

        // 没有任何一行覆盖:只钉对象
        Gate bare = gate(Mode.ASK, RuleSet.EMPTY, RuleSet.EMPTY, cells, Structures.NONE);
        Action drop = Action.drop(Items.DIAMOND);
        assertEquals("drop(minecraft:diamond)",
                bare.consentItem(drop, bare.judge(drop, world), world).remember().toString());
    }

    @Test
    void aRememberedRowLetsTheSameKindThroughNextTime() {
        FakeWorld world = new FakeWorld();
        Map<BlockPos, UUID> cells = new HashMap<>();
        BlockPos first = POS;
        BlockPos second = POS.offset(5, 0, 0);
        for (BlockPos p : List.of(first, second)) {
            world.set(p, Blocks.COBBLESTONE.defaultBlockState());
            cells.put(p, OWNER_ID);
        }
        Gate before = gate(cells);
        Action digFirst = dig(world, first);
        ConsentItem asked = before.consentItem(digFirst, before.judge(digFirst, world), world);

        List<Rule> remembered = ConsentItem.remembered(List.of(asked, asked));
        assertEquals(1, remembered.size(), "同一行只记一次");
        Gate after = gate(Mode.ASK, new RuleSet(List.of(), List.of(), remembered), FactoryRules.layer(), cells,
                Structures.NONE);
        assertTrue(after.judge(dig(world, second), world).allowed(), "记住之后同一种东西不再问,也不靠任务期授权");
    }

    // ==================== 模式 ====================

    @Test
    void bypassAllowsEverythingButHardRulesAndObserveDeniesEverything() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.CHEST.defaultBlockState());
        Map<BlockPos, UUID> cells = Map.of(POS, OWNER_ID);
        Action dig = dig(world, POS);

        assertTrue(gate(Mode.BYPASS, RuleSet.EMPTY, FactoryRules.layer(), cells, Structures.NONE)
                .judge(dig, world).allowed(), "bypass 全放");
        Verdict observed = gate(Mode.OBSERVE, RuleSet.EMPTY, FactoryRules.layer(), cells, Structures.NONE)
                .judge(dig, world);
        assertEquals(Verdict.Kind.DENY, observed.kind());
        assertTrue(observed.reason().contains("observe mode"));
        // observe 连没主人的方块也不动
        world.set(POS.north(), Blocks.DIRT.defaultBlockState());
        assertFalse(gate(Mode.OBSERVE, RuleSet.EMPTY, FactoryRules.layer(), cells, Structures.NONE)
                .judge(dig(world, POS.north()), world).allowed());
    }

    // ==================== 任务期授权 ====================

    @Test
    void aGrantCoversTheSameRuleOnTheSameKindOnly() {
        FakeWorld world = new FakeWorld();
        Map<BlockPos, UUID> cells = new HashMap<>();
        BlockPos first = POS;
        BlockPos second = POS.offset(4, 0, 0);
        BlockPos stone = POS.offset(8, 0, 0);
        for (BlockPos p : List.of(first, second)) {
            world.set(p, Blocks.OAK_LOG.defaultBlockState());
            cells.put(p, OWNER_ID);
        }
        world.set(stone, Blocks.STONE.defaultBlockState());
        cells.put(stone, OWNER_ID);

        Gate ungranted = gate(cells);
        Action digFirst = dig(world, first);
        ConsentItem grant = ungranted.consentItem(digFirst, ungranted.judge(digFirst, world), world);

        Gate granted = new Gate(HER, Layers.policy(Mode.ASK, FactoryRules.hard(), RuleSet.EMPTY, FactoryRules.layer()),
                Structures.NONE, owned(cells), List.of(grant), Hits.NONE);
        assertTrue(granted.judge(digFirst, world).allowed());
        assertTrue(granted.judge(dig(world, second), world).allowed(), "同一行规则问出来的同一种方块:挖一堆只问一次");
        assertTrue(granted.judge(dig(world, stone), world).asks(), "换一种方块另问");
        assertEquals(Verdict.Kind.DENY, new Gate(HER, Layers.policy(Mode.OBSERVE, FactoryRules.hard(), RuleSet.EMPTY,
                        FactoryRules.layer()), Structures.NONE, owned(cells), List.of(grant), Hits.NONE)
                        .judge(digFirst, world).kind(),
                "授权只把问变成放行,解不开拒绝");
    }

    @Test
    void planningNeverCountsAHitOnlyTheLiveQuestionDoes() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.STONE.defaultBlockState());
        Gate planner = new Gate(HER, Layers.policy(Mode.ASK, FactoryRules.hard(), RuleSet.EMPTY, FactoryRules.layer()),
                Structures.NONE, Owners.Blocks.NONE, List.of(), (layer, table, rule) -> {
                    throw new AssertionError("pricing a path counted a hit on " + rule);
                });
        assertTrue(planner.judge(dig(world, POS), world).allowed());
        assertEquals(0, FactoryRules.layer().allow().indexOf(planner.judge(dig(world, POS), world).rule()),
                "逐格定价读得到是哪一行放的,只是不记账");
        assertEquals(Layers.FACTORY, planner.judge(dig(world, POS), world).layer());
    }

    @Test
    void whatCannotBeUndoneIsRegisteredPerVerb() {
        Vocabulary vocabulary = Groups.vocabulary(Groups.registered());
        assertTrue(vocabulary.get("owner").irreversibleOn().contains(Verb.ATTACK), "打死主人的宠物撤不回");
        assertTrue(vocabulary.get("self").irreversibleOn().contains(Verb.ATTACK));
        assertTrue(vocabulary.get("other").irreversibleOn().contains(Verb.ATTACK));
        assertTrue(vocabulary.get("contents").irreversibleOn().contains(Verb.BREAK), "拆装着东西的容器撤不回");
        assertTrue(vocabulary.get("contents").irreversibleOn().contains(Verb.USE_BLOCK), "动容器里的东西撤不回");
        assertTrue(vocabulary.get("named").irreversibleOn().isEmpty(), "打有名字的不算撤不回");
        assertFalse(vocabulary.get("owner").irreversibleOn().contains(Verb.BREAK), "拆主人的墙撤得回(放回去)");
    }
}
