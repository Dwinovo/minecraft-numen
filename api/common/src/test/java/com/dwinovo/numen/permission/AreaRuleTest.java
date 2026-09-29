package com.dwinovo.numen.permission;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.Cells;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 规则项 {@code area:}:解析(整块、一部分、写错了怎么教、哪些动词收它)、匹配(区域里外、别的维度、取反、一部分)、
 * 点名的区域不在了整行不作数、裁决里主人的 deny 行挡住区域里的格、"允许并记住"留着区域那一项。需要 MC 引导。
 */
@Tag("mc")
class AreaRuleTest {

    private static boolean booted;
    private static final BlockPos INSIDE = new BlockPos(2, 64, 2);
    private static final BlockPos DOOR = new BlockPos(5, 65, 0);
    private static final BlockPos OUTSIDE = new BlockPos(30, 64, 30);

    @BeforeAll
    static void boot() {
        booted = FakeWorld.boot();
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过区域规则钉桩");
    }

    /** 主人名下的一块房子:地基 b1,门 b2。 */
    private static Map<String, Area> areas() {
        Area house = Area.of(Level.OVERWORLD, Area.Kind.BOX, Cells.box(new BlockPos(0, 64, 0), new BlockPos(9, 64, 9)))
                .with(Area.Kind.BOX, Cells.box(new BlockPos(4, 65, 0), new BlockPos(5, 66, 0)));
        return Map.of("house", house);
    }

    private static Facts facts(FakeWorld world, net.minecraft.resources.ResourceKey<Level> dim,
                               Map<String, Area> areas) {
        return new Facts(world, new PlacedBlocks(), null, null, dim, areas, java.util.Set.of());
    }

    private static Action dig(FakeWorld world, BlockPos pos) {
        world.set(pos, Blocks.STONE.defaultBlockState());
        return Action.breakBlock(pos, world.getBlockState(pos));
    }

    // ==================== 解析 ====================

    @Test
    void areaTermsParseForVerbsAtABlockAndTeachTheRest() {
        assertEquals("break(area:house)", Rule.parse("break(area:house)").toString());
        assertEquals("is in area house/b2", Rule.parse("break(area:house/b2)").describe());
        Rule.parse("*(area:house & !placed)");
        Rule.parse("take(!area:house)");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Rule.parse("attack(area:house)"))
                .getMessage().contains("area terms match actions at a block"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Rule.parse("break(area:My House)"))
                .getMessage().contains("area names are lowercase"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Rule.parse("break(area:house/door)"))
                .getMessage().contains("a letter and a number"));
    }

    // ==================== 匹配 ====================

    @Test
    void anAreaTermHitsCellsInsideTheAreaInItsDimension() {
        FakeWorld world = new FakeWorld();
        Facts here = facts(world, Level.OVERWORLD, areas());
        Rule house = Rule.parse("break(area:house)");
        assertTrue(house.matches(dig(world, INSIDE), here));
        assertTrue(house.matches(dig(world, DOOR), here));
        assertFalse(house.matches(dig(world, OUTSIDE), here));
        assertFalse(house.matches(dig(world, INSIDE), facts(world, Level.NETHER, areas())),
                "下界里同一个坐标不在主世界的房子里");
        assertTrue(Rule.parse("break(!area:house)").matches(dig(world, OUTSIDE), here), "取反:区域外");
        assertTrue(Rule.parse("place(area:house)").matches(
                Action.place(INSIDE, Blocks.AIR.defaultBlockState(), Items.DIRT), here));
        assertFalse(Rule.parse("*(area:house)").matches(Action.drop(Items.DIRT), here), "没有位置的动作不在任何区域里");

        Rule door = Rule.parse("break(area:house/b2)");
        assertTrue(door.matches(dig(world, DOOR), here));
        assertFalse(door.matches(dig(world, INSIDE), here), "只认门那一部分");
    }

    @Test
    void aRowNamingAMissingAreaMatchesNothingEvenNegated() {
        FakeWorld world = new FakeWorld();
        Facts noAreas = facts(world, Level.OVERWORLD, Map.of());
        assertFalse(Rule.parse("break(area:house)").matches(dig(world, INSIDE), noAreas));
        assertFalse(Rule.parse("break(!area:house)").matches(dig(world, OUTSIDE), noAreas),
                "说不清管哪儿的一行不放行也不拒绝");
        Facts withHouse = facts(world, Level.OVERWORLD, areas());
        assertFalse(Rule.parse("break(!area:house/b7)").matches(dig(world, OUTSIDE), withHouse), "没有这一部分同理");

        assertEquals(List.of(), Rule.parse("break(area:house & !area:house/b2)").missingAreas(areas()));
        assertEquals(List.of("barn", "house/b7"),
                Rule.parse("break(area:barn & !area:house/b7 & !placed)").missingAreas(areas()));
        assertEquals(List.of(), Rule.parse("break(placed)").missingAreas(Map.of()));
    }

    // ==================== 裁决 ====================

    @Test
    void anOwnersDenyRowGuardsTheAreaAndTheFactoryRowsRunOutside() {
        FakeWorld world = new FakeWorld();
        RuleSet owner = new RuleSet(List.of(Rule.parse("break(area:house)")), List.of(), List.of());
        Gate gate = new Gate(null, Mode.ASK, owner, RuleSet.factory(), Level.OVERWORLD, new PlacedBlocks(), areas(),
                List.of());
        Verdict inside = gate.judge(dig(world, INSIDE), world);
        assertEquals(Verdict.Kind.DENY, inside.kind());
        assertTrue(inside.reason().contains("break(area:house)") && inside.reason().contains("is in area house"),
                inside.reason());
        assertTrue(gate.judge(dig(world, OUTSIDE), world).allowed(), "区域外的自然石头照出厂 allow 行");

        Gate nether = new Gate(null, Mode.ASK, owner, RuleSet.factory(), Level.NETHER, new PlacedBlocks(), areas(),
                List.of());
        assertTrue(nether.judge(dig(world, INSIDE), world).allowed(), "别的维度不受这块区域管");
    }

    @Test
    void rememberingAnAskedAreaRowKeepsTheArea() {
        FakeWorld world = new FakeWorld();
        RuleSet owner = new RuleSet(List.of(), List.of(Rule.parse("break(area:house)")), List.of());
        Gate gate = new Gate(null, Mode.ASK, owner, RuleSet.factory(), Level.OVERWORLD, new PlacedBlocks(), areas(),
                List.of());
        Action stone = dig(world, INSIDE);
        Verdict asked = gate.judge(stone, world);
        assertEquals(Verdict.Kind.ASK, asked.kind());
        ConsentItem item = gate.consentItem(stone, asked, world);
        assertEquals("break(area:house & minecraft:stone)", item.remember().toString(),
                "记下的是房子里的石头,不是哪儿的石头都行");
    }

    // ==================== 改区域 ====================

    @Test
    void editAreaTakesWholeAreaTermsAndTheRuledSignal() {
        assertEquals("edit_area(ruled)", Rule.parse("edit_area(ruled)").toString());
        Rule.parse("edit_area(area:ores & !ruled)");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Rule.parse("edit_area(area:house/b2)"))
                .getMessage().contains("edit_area changes a whole area"));
        assertEquals(List.of("house", "barn"),
                Rule.parse("*(area:house/b2 & !area:barn)").areaNames(), "点名的区域名不带部分,取反的也算");
    }

    /**
     * 主人的规则点名的区域(deny、ask、allow 哪张表都算,区域此刻在不在都算)改之前要问;没点名的照出厂 allow 行放行。
     * 新建一块和规则点名的区域同名的,也是改它。
     */
    @Test
    void editingAnAreaTheOwnersRulesNameAsksAndOthersPass() {
        RuleSet owner = new RuleSet(List.of(Rule.parse("break(area:house)")), List.of(),
                List.of(Rule.parse("break(area:free)")));
        Gate gate = new Gate(null, Mode.ASK, owner, RuleSet.factory(), Level.OVERWORLD, new PlacedBlocks(), areas(),
                List.of());
        Verdict house = gate.judge(Action.editArea("house"), null);
        assertEquals(Verdict.Kind.ASK, house.kind());
        assertEquals("edit_area(ruled)", house.rule().toString());
        assertTrue(house.reason().contains("is an area your owner's rules name"), house.reason());
        assertEquals(Verdict.Kind.ASK, gate.judge(Action.editArea("free"), null).kind(),
                "allow 行点名、此刻还不存在的区域:建它就是替主人放宽");
        assertTrue(gate.judge(Action.editArea("ores"), null).allowed(), "没被点名的区域照常改");
    }

    @Test
    void theOwnerCanLoosenOrTightenEditingAreas() {
        RuleSet loose = new RuleSet(List.of(Rule.parse("break(area:house)")), List.of(),
                List.of(Rule.parse("edit_area(area:house)")));
        Gate gate = new Gate(null, Mode.ASK, loose, RuleSet.factory(), Level.OVERWORLD, new PlacedBlocks(), areas(),
                List.of());
        assertTrue(gate.judge(Action.editArea("house"), null).allowed(), "主人放开了这一块");

        RuleSet tight = new RuleSet(List.of(Rule.parse("edit_area(*)")), List.of(), List.of());
        Gate strict = new Gate(null, Mode.ASK, tight, RuleSet.factory(), Level.OVERWORLD, new PlacedBlocks(), areas(),
                List.of());
        assertEquals(Verdict.Kind.DENY, strict.judge(Action.editArea("ores"), null).kind());
    }

    @Test
    void rememberingAnAreaEditKeepsThatArea() {
        RuleSet owner = new RuleSet(List.of(Rule.parse("break(area:house)")), List.of(), List.of());
        Gate gate = new Gate(null, Mode.ASK, owner, RuleSet.factory(), Level.OVERWORLD, new PlacedBlocks(), areas(),
                List.of());
        Action edit = Action.editArea("house");
        ConsentItem item = gate.consentItem(edit, gate.judge(edit, null), null);
        assertEquals("edit_area(ruled & area:house)", item.remember().toString(), "记下的只是这一块");
        assertEquals("house", item.subject());
        Gate granted = new Gate(null, Mode.ASK, owner, RuleSet.factory(), Level.OVERWORLD, new PlacedBlocks(),
                areas(), List.of(item));
        assertTrue(granted.judge(edit, null).allowed(), "答应过的这一块不再问");
        RuleSet remembered = new RuleSet(owner.deny(), List.of(), List.of(item.remember()));
        Gate later = new Gate(null, Mode.ASK, remembered, RuleSet.factory(), Level.OVERWORLD, new PlacedBlocks(),
                areas(), List.of());
        assertTrue(later.judge(edit, null).allowed());
    }
}
