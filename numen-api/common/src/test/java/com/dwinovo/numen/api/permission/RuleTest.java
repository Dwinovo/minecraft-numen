package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.FakeWorld;
import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.agent.scope.Handle;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.RuleSet;
import com.dwinovo.numen.permission.Verb;
import com.dwinovo.numen.permission.Verdict;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 规则对游戏动作的匹配:解析与语法在规则核心的单测里({@code permission} 模块);这里钉的是项对着世界读出来的
 * 事实——自带的对象组与属性(谁的、近旁有谁的)、id、标签,名字表对规则文本的把关,以及登记的词({@link RuleWord})怎么进名字表、
 * 怎么被问。需要 MC 注册表。
 */
@Tag("mc")
class RuleTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID HER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final Actor HER = new Actor(HER_ID, "Aria", OWNER_ID);
    private static final PlacedBlocks.Placer OWNERS = new PlacedBlocks.Placer(OWNER_ID, "Steve");
    private static final PlacedBlocks.Placer HERS = new PlacedBlocks.Placer(HER_ID, "Aria", OWNER_ID);
    private static final PlacedBlocks.Placer STRANGERS =
            new PlacedBlocks.Placer(UUID.fromString("00000000-0000-0000-0000-0000000000dd"), "Alex");

    private static boolean booted;
    private static final BlockPos POS = new BlockPos(1, 64, 1);

    @BeforeAll
    static void boot() {
        booted = FakeWorld.boot();
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过规则钉桩");
    }

    private static Facts facts(FakeWorld world, PlacedBlocks placed, Actor actor) {
        return new Facts(world, placed, null, actor, Structures.NONE, false, Groups.registered());
    }

    private static boolean matches(String rule, Action action, Facts facts) {
        return Groups.parse(rule).matches(new ActView(action, facts));
    }

    // ==================== 名字表把关 ====================

    @Test
    void theVocabularyTeachesWhatItKnowsAndChecksArguments() {
        Rule.Mistake unknown = assertThrows(Rule.Mistake.class, () -> Groups.parse("break(placed_by_players)"));
        assertEquals("name", unknown.code());
        assertTrue(unknown.args().get(2).contains("owner") && unknown.args().get(2).contains("near(owner)")
                        && unknown.args().get(2).contains("structure(#minecraft:village)"),
                "教学式的错误列出认得的组与属性: " + unknown.args().get(2));
        assertEquals("argument_missing", assertThrows(Rule.Mistake.class, () -> Groups.parse("place(near)")).code());
        assertEquals("argument_bad", assertThrows(Rule.Mistake.class, () -> Groups.parse("place(near(nature))")).code());
        assertEquals("argument_bad", assertThrows(Rule.Mistake.class,
                () -> Groups.parse("break(structure(minecraft:village))")).code(), "结构类型写成标签");
        assertEquals("argument_unexpected", assertThrows(Rule.Mistake.class, () -> Groups.parse("break(owner(x))")).code());
        assertEquals("break(structure(#minecraft:village))", Groups.parse("break(structure(#minecraft:village))").toString());
        assertEquals("break(structure & !structure(#minecraft:village))",
                Groups.parse("break(structure & !structure(#minecraft:village))").toString());
    }

    // ==================== 匹配 ====================

    @Test
    void ownershipGroupsReadThePlacementRecordRelativeToTheActor() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.COBBLESTONE.defaultBlockState());
        PlacedBlocks placed = new PlacedBlocks();
        Action dig = Action.breakBlock(POS, world.getBlockState(POS));

        assertFalse(matches("break(owner | other | self)", dig, facts(world, placed, HER)), "没有记号:谁都没放过");
        placed.record(POS, HERS);
        assertTrue(matches("break(self)", dig, facts(world, placed, HER)));
        assertFalse(matches("break(owner | other)", dig, facts(world, placed, HER)), "她自己放的不是别人放的");
        assertTrue(matches("break(!owner & !other)", dig, facts(world, placed, HER)), "取反与与");

        placed.record(POS, OWNERS);
        assertTrue(matches("break(owner)", dig, facts(world, placed, HER)));
        assertTrue(matches("break(owner | other)", dig, facts(world, placed, HER)));
        placed.record(POS, STRANGERS);
        assertTrue(matches("break(other)", dig, facts(world, placed, HER)));
        assertFalse(matches("break(owner)", dig, facts(world, placed, HER)));

        // 同一格,换一个要动手的人:另一只同伴拆她的,是主人名下另一只放的,在主人这一边
        placed.record(POS, HERS);
        Actor sister = new Actor(UUID.randomUUID(), "Bea", OWNER_ID);
        assertTrue(matches("break(owner)", dig, facts(world, placed, sister)));
        // 不知道是谁动手,也不认识主人:放的人谁都不是
        assertTrue(matches("break(other)", dig, facts(world, placed, Actor.NOBODY)));
    }

    @Test
    void nearReadsTheNeighbourhoodByWhoPlacedWhatIsThere() {
        FakeWorld world = new FakeWorld();
        PlacedBlocks placed = new PlacedBlocks();
        Action lava = Action.place(POS.above(), Blocks.AIR.defaultBlockState(), Items.LAVA_BUCKET);
        world.set(POS, Blocks.COBBLESTONE.defaultBlockState());
        placed.record(POS, HERS);
        assertFalse(matches("place(near(owner) | near(other))", lava, facts(world, placed, HER)));
        assertTrue(matches("place(near(self))", lava, facts(world, placed, HER)));
        world.set(POS.east(), Blocks.COBBLESTONE.defaultBlockState());
        placed.record(POS.east(), OWNERS);
        assertTrue(matches("place(near(owner))", lava, facts(world, placed, HER)));
        assertFalse(matches("place(near(other))", lava, facts(world, placed, HER)));
        world.set(POS.west(), Blocks.COBBLESTONE.defaultBlockState());
        placed.record(POS.west(), STRANGERS);
        assertTrue(matches("place(near(other))", lava, facts(world, placed, HER)));
        assertFalse(matches("place(near(other))", Action.place(POS.offset(9, 1, 0), Blocks.AIR.defaultBlockState(),
                Items.LAVA_BUCKET), facts(world, placed, HER)), "离得远不算");
    }

    @Test
    void idAndTagTermsNameKinds() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.CHEST.defaultBlockState());
        Action dig = Action.breakBlock(POS, world.getBlockState(POS));
        Facts f = facts(world, new PlacedBlocks(), HER);
        assertTrue(matches("break(minecraft:chest)", dig, f));
        assertFalse(matches("break(minecraft:barrel)", dig, f));
        // 标签内容来自数据包,无头引导下手动绑:石头绑进门标签,钉的是"标签项生效"
        bindBlockTags(Map.of(BlockTags.DOORS, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.STONE))));
        try {
            world.set(POS, Blocks.STONE.defaultBlockState());
            Action digStone = Action.breakBlock(POS, world.getBlockState(POS));
            assertTrue(matches("break(#minecraft:doors)", digStone, f));
            assertFalse(matches("break(#minecraft:beds)", digStone, f));
        } finally {
            bindBlockTags(Map.of());
        }
        // 放置看的是要放的方块,不是被盖掉的那格
        Action placeTnt = Action.place(POS, Blocks.AIR.defaultBlockState(), Items.TNT);
        assertTrue(matches("place(minecraft:tnt)", placeTnt, f));
        assertTrue(matches("drop(minecraft:diamond)", Action.drop(Items.DIAMOND), f));
    }

    // ==================== 登记的词 ====================

    /** 测试用的一个词:石头算"亮的",拆亮的东西撤不回。 */
    private static final RuleWord SHINY = new RuleWord("is shiny", Component.translatable("gt.rule_word.shiny"),
            Set.of(Verb.BREAK), view -> view.action().state() != null && view.action().state().is(Blocks.STONE));

    @Test
    void aRegisteredWordIsUsableInARuleAndIsAskedAboutTheAction() {
        Handle word = Questions.RULE_WORD.register("gt_shiny", SHINY);
        try {
            FakeWorld world = new FakeWorld();
            world.set(POS, Blocks.STONE.defaultBlockState());
            world.set(POS.east(), Blocks.DIRT.defaultBlockState());
            Facts f = facts(world, new PlacedBlocks(), HER);
            Action stone = Action.breakBlock(POS, world.getBlockState(POS));
            Action dirt = Action.breakBlock(POS.east(), world.getBlockState(POS.east()));
            assertEquals("break(gt_shiny & !owner)", Groups.parse("break( gt_shiny&!owner )").toString());
            assertTrue(matches("break(gt_shiny)", stone, f));
            assertFalse(matches("break(gt_shiny)", dirt, f));
            assertTrue(matches("break(!gt_shiny)", dirt, f));
            assertEquals("argument_unexpected",
                    assertThrows(Rule.Mistake.class, () -> Groups.parse("break(gt_shiny(x))")).code(), "登记的词不带参数");

            // 一行写着它的规则问出来的征询:理由是它的自述,给主人看的是它登记的那一句,撤不回照它登记的动词标
            Gate gate = new Gate(HER, Layers.policy(Mode.ASK, List.of(),
                    new RuleSet(List.of(), List.of(Groups.parse("break(gt_shiny)")), List.of()), RuleSet.EMPTY),
                    new PlacedBlocks(), Structures.NONE, false, List.of(), Hits.NONE);
            Verdict verdict = gate.judge(stone, world);
            assertTrue(verdict.asks());
            assertEquals("is shiny", verdict.cause());
            ConsentItem item = gate.consentItem(stone, verdict, world);
            assertEquals(Component.empty().append(Component.translatable("gt.rule_word.shiny")), item.shownCause());
            assertTrue(item.irreversible(), "这个词登记了在 break 下撤不回");
            assertEquals("break(gt_shiny & minecraft:stone)", item.remember().toString());
        } finally {
            word.close();
        }
    }

    @Test
    void aWordNobodyRegisteredIsAParseErrorNamingTheKnownWords() {
        Handle word = Questions.RULE_WORD.register("gt_shiny", SHINY);
        try {
            Rule.Mistake unknown = assertThrows(Rule.Mistake.class, () -> Groups.parse("break(gt_dull)"));
            assertEquals("name", unknown.code());
            assertEquals("gt_dull", unknown.args().get(0));
            assertTrue(unknown.args().get(2).startsWith("self, owner, other, structure(#minecraft:village), nature, "
                    + "unknown, block_entity, named, near(owner)"), "自带的在前: " + unknown.args().get(2));
            assertTrue(List.of(unknown.args().get(2).split(", ")).contains("gt_shiny"),
                    "登记着的词也在认得的名字里: " + unknown.args().get(2));
        } finally {
            word.close();
        }
        Rule.Mistake withdrawn = assertThrows(Rule.Mistake.class, () -> Groups.parse("break(gt_shiny)"));
        assertEquals("name", withdrawn.code(), "登记撤回之后,写着它的规则解析不了");
        assertFalse(List.of(withdrawn.args().get(2).split(", ")).contains("gt_shiny"));
    }

    @Test
    void aWordWithdrawnAfterARuleWasParsedHoldsForNothing() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.STONE.defaultBlockState());
        Action stone = Action.breakBlock(POS, world.getBlockState(POS));
        Handle word = Questions.RULE_WORD.register("gt_shiny", SHINY);
        Rule rule;
        try {
            rule = Groups.parse("break(gt_shiny)");
            assertTrue(rule.matches(new ActView(stone, facts(world, new PlacedBlocks(), HER))));
        } finally {
            word.close();
        }
        assertFalse(rule.matches(new ActView(stone, facts(world, new PlacedBlocks(), HER))), "没人认得这个词了");
    }

    @Test
    void aWordIsRegisteredOnceAndNotUnderABuiltInName() {
        Handle word = Questions.RULE_WORD.register("gt_shiny", SHINY);
        try {
            assertThrows(IllegalArgumentException.class, () -> Questions.RULE_WORD.register("gt_shiny", SHINY), "重名");
        } finally {
            word.close();
        }
        for (String builtIn : Groups.names()) {
            IllegalArgumentException taken = assertThrows(IllegalArgumentException.class,
                    () -> Questions.RULE_WORD.register(builtIn, SHINY), builtIn);
            assertTrue(taken.getMessage().contains("'" + builtIn + "'"), taken.getMessage());
        }
        assertEquals(Set.of("self", "owner", "other", "structure", "nature", "unknown", "block_entity", "named", "near"),
                Groups.names(), "自带的词就这几个:不认识任何具体东西的");
        assertThrows(IllegalArgumentException.class, () -> Questions.RULE_WORD.register("Shiny", SHINY), "名字不合规");
        assertThrows(IllegalArgumentException.class, () -> Questions.RULE_WORD.register("mod:shiny", SHINY));
    }

    @Test
    void aFactoryRowWhoseWordNobodyRegisteredIsLeftOutUntilSomeoneDoes() {
        // 出厂规则写着 hazard_item,那是认得原版物品的一方登记的词:没人登记时那几行不成规则,登记上了就在
        assertFalse(FactoryRules.hard().stream().anyMatch(r -> r.toString().equals("place(hazard_item & near(other))")));
        assertTrue(FactoryRules.hard().stream().anyMatch(r -> r.toString().equals("break(other)")), "认得的行照旧");
        Handle word = Questions.RULE_WORD.register("hazard_item", SHINY);
        try {
            assertTrue(FactoryRules.hard().stream().anyMatch(r -> r.toString().equals("place(hazard_item & near(other))")));
            assertTrue(FactoryRules.layer().allow().stream().anyMatch(r -> r.toString().equals("place(!hazard_item)")));
        } finally {
            word.close();
        }
        assertFalse(FactoryRules.layer().allow().stream().anyMatch(r -> r.toString().equals("place(!hazard_item)")));
    }

    @SuppressWarnings("unchecked")
    private static void bindBlockTags(Map<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>,
            List<net.minecraft.core.Holder<net.minecraft.world.level.block.Block>>> tags) {
        ((net.minecraft.core.MappedRegistry<net.minecraft.world.level.block.Block>) BuiltInRegistries.BLOCK)
                .bindTags(tags);
    }
}
