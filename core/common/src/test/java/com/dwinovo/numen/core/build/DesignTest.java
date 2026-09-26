package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.CoreCommandsFixture;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.core.task.build.ReplaceMode;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设计的文本、画法与摆法,以及把一处变成设计的样子要动哪些格。文本每行一条原语命令,读与写过同一个解析器;画在空的底子上,
 * 后写覆盖先写;摆到落点时原点落在锚点、绕原点转;差异分补、换、拆三类,拆只拆这一栋记录里有、世界里仍是那个方块的格。
 * 执行与持久化在真世界里的样子在 GameTest 里验。
 */
class DesignTest {

    private static final UUID OWNER = UUID.fromString("0b5c1f1e-3a0e-4c7f-9d2a-5f1e2d3c4b5a");

    @BeforeAll
    static void install() {
        CoreCommandsFixture.install();
    }

    private static String text(String... steps) {
        return "# Numen design house\n# author: Aria\n# owner: " + OWNER + " Dwin\n# created: 2026-09-26T08:00:00Z\n"
                + String.join("\n", steps) + "\n";
    }

    @Test
    void aDesignTextReadsStepByStepAndWritesBackTheSameText() {
        Design design = Design.parse("house", text(
                "build layer 0 0 0 ### #.# ### --block stone_bricks --up_to 2",
                "",
                "# the door",
                "build set oak_door[facing=south] 1 0 2"));
        assertEquals(List.of("build layer 0 0 0 ### #.# ### --block stone_bricks --up_to 2",
                "build set oak_door[facing=south] 1 0 2"), design.steps(), "空行与注释不是步骤");
        assertEquals(OWNER, design.owner());
        assertEquals("Dwin", design.ownerName());
        assertEquals("Aria", design.author());
        assertEquals(design.steps(), Design.parse("house", design.text()).steps(), "写出去的文本读回来是同样几步");
        assertEquals(3 * 8, design.drawn().targets().size(), "墙圈三层每层八格;门盖掉南面正中那一格,不多出一格");
    }

    @Test
    void aStepIsWrittenBackInOneCanonicalLine() {
        Design.Step step = Design.step("build layer 0 1 0 \"# #\" --up_to 3 --block \"oak_planks*8, spruce_planks\"");
        assertEquals(Primitive.LAYER, step.primitive());
        assertEquals("build layer 0 1 0 \"# #\" --block \"oak_planks*8, spruce_planks\" --up_to 3", step.line(),
                "标志按参数表的顺序写,带空格的值加引号");
        assertEquals(step.line(), Design.step(step.line()).line());
    }

    @Test
    void aLineThatDoesNotReadOrDrawIsReportedByItsLineInTheFile() {
        IllegalArgumentException typo = assertThrows(IllegalArgumentException.class, () -> Design.parse("house",
                text("build set stone 0 0 0", "build layr 0 0 0 ###")));
        assertTrue(typo.getMessage().startsWith("line 6 (build layr 0 0 0 ###): "), typo.getMessage());
        assertTrue(typo.getMessage().contains("Did you mean: layer?"), "报错和执行时写错一样: " + typo.getMessage());
        IllegalArgumentException block = assertThrows(IllegalArgumentException.class, () -> Design.parse("house",
                text("build set notablock 0 0 0")));
        assertTrue(block.getMessage().startsWith("line 5 (build set notablock 0 0 0): notablock"), block.getMessage());
        IllegalArgumentException into = assertThrows(IllegalArgumentException.class, () -> Design.parse("house",
                text("build set stone 0 0 0 --into house")));
        assertTrue(into.getMessage().contains("--into"), into.getMessage());
        IllegalArgumentException other = assertThrows(IllegalArgumentException.class, () -> Design.parse("house",
                text("build new shed")));
        assertTrue(other.getMessage().contains("one build primitive"), other.getMessage());
    }

    @Test
    void changingTheStepsRedrawsTheWholeDesignAndNamesTheStepThatFails() {
        Design fresh = Design.fresh("house", OWNER, "Dwin", "Aria", "2026-09-26T08:00:00Z");
        Design two = fresh.withSteps(List.of("build set stone 0 0 0", "build set glass 1 0 0"));
        assertEquals(2, two.drawn().targets().size());
        IllegalArgumentException legend = assertThrows(IllegalArgumentException.class,
                () -> two.withSteps(List.of("build set stone 0 0 0", "build layer 0 0 0 #X# --legend #=stone")));
        assertTrue(legend.getMessage().startsWith("step 2 (build layer 0 0 0 #X# --legend #=stone): layer: character 'X'"),
                legend.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Design.fresh("My House", OWNER, "", "", ""),
                "设计名只用小写字母、数字、_ 与 -");
    }

    @Test
    void laterStepsOverwriteEarlierCells() {
        Design design = Design.parse("house", text("build layer 0 0 0 ### --block stone", "build set air 1 0 0"));
        Map<BlockPos, BlockState> cells = new HashMap<>();
        design.drawn().targets().forEach(t -> cells.put(t.pos(), t.desiredState()));
        assertTrue(cells.get(new BlockPos(1, 0, 0)).isAir(), "后一步把中间那格写成了空气");
        assertTrue(cells.get(new BlockPos(0, 0, 0)).is(Blocks.STONE));
        assertEquals(List.of(3, 1), design.drawn().steps().stream().map(List::size).toList(),
                "每一步各自画了几格,被覆盖的也算在那一步里");
    }

    @Test
    void relativeCellsLandOnTheAnchorAndTurnClockwiseAboutTheOrigin() {
        Design design = Design.parse("house", text("build set oak_stairs[facing=east] 2 0 1", "build set stone 0 0 0"));
        BlockPos anchor = new BlockPos(100, 64, 50);
        Layout straight = design.drawn().laid(new Placement(anchor, 0));
        assertEquals(new BlockPos(102, 64, 51), straight.targets().get(0).pos());
        Layout turned = design.drawn().laid(new Placement(anchor, Placement.quarters(90)));
        BuildTaskRecord.Target stairs = turned.targets().get(0);
        assertEquals(new BlockPos(99, 64, 52), stairs.pos(), "顺时针 90°:(x, z) 转到 (-z, x)");
        assertEquals(Direction.SOUTH, stairs.desiredState().getValue(StairBlock.FACING), "朝东的楼梯转过去朝南");
        assertEquals(anchor, turned.targets().get(1).pos(), "原点永远在锚点上");
        assertThrows(IllegalArgumentException.class, () -> Placement.quarters(45));
    }

    @Test
    void aCopyInADesignCopiesTheDesignsOwnEarlierSteps() {
        Design design = Design.parse("house", text("build set stone 0 0 0", "build copy 0 0 0 0 0 0 5 0 0"));
        assertTrue(design.drawn().targets().stream()
                .anyMatch(t -> t.pos().equals(new BlockPos(5, 0, 0)) && t.desiredState().is(Blocks.STONE)));
        IllegalArgumentException nothing = assertThrows(IllegalArgumentException.class,
                () -> Design.parse("house", text("build copy 10 0 0 12 0 0 0 0 0")),
                "设计的底子是空的,抄不到世界里的东西");
        assertTrue(nothing.getMessage().contains("nothing to copy"), nothing.getMessage());
    }

    private static BuildTaskRecord.Target target(BlockPos pos, BlockState state) {
        return new BuildTaskRecord.Target(state, state.getBlock().asItem(), pos, "x");
    }

    @Test
    void theChangesAreWhatToAddWhatToReplaceAndOnlyWhatIsHersToRemove() {
        BlockPos add = new BlockPos(0, 0, 0);
        BlockPos swap = new BlockPos(1, 0, 0);
        BlockPos same = new BlockPos(2, 0, 0);
        BlockPos kept = new BlockPos(3, 0, 0);
        BlockPos hers = new BlockPos(0, 1, 0);
        BlockPos changedSince = new BlockPos(1, 1, 0);
        BlockPos farAway = new BlockPos(2, 1, 0);
        Map<BlockPos, BlockState> world = new HashMap<>();
        world.put(swap, Blocks.OAK_PLANKS.defaultBlockState());
        world.put(same, Blocks.STONE.defaultBlockState());
        world.put(kept, Blocks.GOLD_BLOCK.defaultBlockState());
        world.put(hers, Blocks.STONE.defaultBlockState());
        world.put(changedSince, Blocks.DIAMOND_BLOCK.defaultBlockState());
        Function<BlockPos, BlockState> seen = pos -> pos.equals(farAway) ? null
                : world.getOrDefault(pos, Blocks.AIR.defaultBlockState());

        Built built = new Built();
        Built.Site site = new Built.Site("house", ResourceLocation.parse("minecraft:overworld"), BlockPos.ZERO, 0);
        for (BlockPos pos : List.of(swap, same, hers, changedSince, farAway)) {
            built.placed(site, "Aria", 0L, pos, pos.equals(swap) ? Blocks.OAK_PLANKS : Blocks.STONE);
        }
        List<BuildTaskRecord.Target> design = List.of(
                target(add, Blocks.STONE.defaultBlockState()),
                target(swap, Blocks.GLASS.defaultBlockState()),
                target(same, Blocks.STONE.defaultBlockState()),
                target(kept, Blocks.STONE.defaultBlockState()).withMask(ReplaceMode.DONT_REPLACE));

        Changes changes = Changes.between(design, built.at(site), seen);
        assertEquals(1, changes.fill(), "空着的补上");
        assertEquals(1, changes.replace(), "立着别的换掉");
        assertEquals(1, changes.remove(), "设计里没有了、她放的、世界里仍是它的,拆");
        assertEquals(1, changes.unseen(), "看不见的那一格到了再对照");
        List<BuildTaskRecord.Target> removals = changes.work().stream().filter(t -> t.removes() != null).toList();
        assertEquals(List.of(hers, farAway), removals.stream().map(BuildTaskRecord.Target::pos).toList(),
                "被人换成钻石块的那一格不拆");
        assertTrue(removals.stream().allMatch(t -> t.removes() == Blocks.STONE && t.desiredState().isAir()
                && t.mode() == ReplaceMode.REPLACE_EMPTY));
        assertEquals(design.size() + 2, changes.work().size(), "交给执行器的是设计的全部格加上要拆的格");

        Changes firstTime = Changes.between(design, null, seen);
        assertEquals(0, firstTime.remove(), "第一次盖没有记录,不拆任何东西");
        world.put(add, Blocks.STONE.defaultBlockState());
        world.put(swap, Blocks.GLASS.defaultBlockState());
        assertTrue(Changes.between(design, null, pos -> world.getOrDefault(pos, Blocks.AIR.defaultBlockState()))
                .none(), "都对上了就没有要动的");
    }

    @Test
    void aBuildingsRecordSurvivesASave() {
        Built built = new Built();
        Built.Site site = new Built.Site("house", ResourceLocation.parse("minecraft:overworld"),
                new BlockPos(10, 64, -5), 1);
        built.placed(site, "Aria", 24000L, new BlockPos(10, 64, -5), Blocks.STONE);
        built.placed(site, "Aria", 24010L, new BlockPos(11, 64, -5), Blocks.OAK_PLANKS);
        built.placed(site, "Aria", 24020L, new BlockPos(12, 64, -5), Blocks.STONE);
        built.cleared(site, 24030L, new BlockPos(11, 64, -5));
        Built.Site other = new Built.Site("house", ResourceLocation.parse("minecraft:overworld"), BlockPos.ZERO, 0);
        built.placed(other, "Mio", 30000L, BlockPos.ZERO, Blocks.GLASS);

        Built read = Built.load(built.save(new CompoundTag(), null), null);
        Built.Building first = read.at(site);
        assertEquals("house#1", first.name());
        assertEquals("house#2", read.at(other).name(), "同一份设计换个落点是另一栋");
        assertEquals(Map.of(new BlockPos(10, 64, -5).asLong(), Blocks.STONE, new BlockPos(12, 64, -5).asLong(),
                Blocks.STONE), first.cells());
        assertEquals(1, first.quarters());
        assertEquals("Aria", first.builder());
        assertEquals(24000L, first.builtAt());
        assertEquals(24030L, first.changedAt());
        assertNull(read.at(new Built.Site("shed", ResourceLocation.parse("minecraft:overworld"), BlockPos.ZERO, 0)));
    }
}
