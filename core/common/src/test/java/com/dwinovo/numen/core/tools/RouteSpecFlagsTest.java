package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.cli.CommandTool;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.core.pathing.spec.CellClass;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 路线规格的标志到 {@link RouteSpec} 的翻译:每个标志落到规格的哪一项,坐标格与坐标盒进位置表,以及每种写错都报
 * 教学式错误——写法上的错由参数类型在解析时报,意思不成立的由翻译报。标志从 {@code command} 工具一整行进来,和她写的
 * 一样;方块 id 与标签那几条需要 MC 注册表,无头引导失败时跳过。
 */
@Tag("mc")
class RouteSpecFlagsTest {

    private static boolean booted;
    /** 夹具动作的默认规格:翻译叠在它上面。 */
    private static final AtomicReference<RouteSpec> BASE = new AtomicReference<>(RouteSpec.defaults());
    private static final AtomicReference<RouteSpec> LAST = new AtomicReference<>();

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
        AtomicReference<NumenApi> door = new AtomicReference<>();
        NumenPlugins.register(door::set);
        door.get().registerCommands("gt_route", "Test fixture: route flags read into a spec.", g ->
                g.server("plan", "Read the route flags.", (src, args) -> {
                    LAST.set(RouteSpecFlags.parse(args, BASE.get()));
                    src.reply(TaskResult.ok("read").toJson());
                }, RouteSpecFlags.PARAMS.toArray(Param<?>[]::new))
                        .example("gt_route plan --alter natural --avoid water")
                        .promote("gt_route_plan", "Read the route flags, as a tool."));
    }

    /** 一整行交 {@code command} 工具,和她写的一样;回执失败时返回 null。 */
    private static RouteSpec spec(String flags) {
        String reply = run(flags);
        JsonObject result = JsonParser.parseString(reply).getAsJsonObject();
        assertTrue(result.get("success").getAsBoolean(), reply);
        return LAST.get();
    }

    private static String error(String flags) {
        String reply = run(flags);
        JsonObject result = JsonParser.parseString(reply).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean(), flags + " should fail");
        return result.get("message").getAsString();
    }

    private static String run(String flags) {
        LAST.set(null);
        JsonObject args = new JsonObject();
        args.addProperty("command", ("gt_route plan " + flags).strip());
        List<String> replies = new ArrayList<>();
        new CommandTool().serve("test-call", args, null, replies::add);
        assertEquals(1, replies.size(), "恰好一次回执: " + replies);
        return replies.get(0);
    }

    /** mine 的规格叠在它自己的默认上:没写的保持默认,写了的覆盖,禁令并进默认已有的。 */
    @Test
    void flagsLayOverTheCallersDefault() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过方块 id 解析");
        RouteSpec base = RouteSpec.defaults().withAlter(RouteSpec.Alter.ANY)
                .withBans(new RouteSpec.BlockBans(Set.of(Blocks.CHEST), Set.of(), Set.of()));
        BASE.set(base);
        try {
            assertSame(base, spec(""));
            RouteSpec kept = spec("--avoid_break 1,2,3 minecraft:oak_log");
            assertEquals(RouteSpec.Alter.ANY, kept.alter());
            assertEquals(COST_INF, kept.positions().dig(new BlockPos(1, 2, 3).asLong()));
            assertTrue(kept.bans().breaking().contains(Blocks.CHEST));
            assertTrue(kept.bans().breaking().contains(Blocks.OAK_LOG));
            assertEquals(RouteSpec.Alter.NATURAL, spec("--alter natural").alter());
        } finally {
            BASE.set(RouteSpec.defaults());
        }
    }

    @Test
    void noFlagsIsTheFactorySpec() {
        RouteSpec s = spec("");
        assertSame(RouteSpec.defaults(), s);
        assertEquals(RouteSpec.Alter.NONE, s.alter());
        assertTrue(s.positions().isEmpty());
        assertTrue(s.bans().isEmpty());
    }

    @Test
    void everyFlagLandsOnItsField() {
        RouteSpec s = spec("--alter natural --avoid water door --penalty_place 5 --penalty_break 7.5 "
                + "--penalty_jump 9 --penalty_wade 0 --parkour true --climb_vines true --max_fall 6 --alter_budget 4");
        assertEquals(RouteSpec.Alter.NATURAL, s.alter());
        assertEquals(RouteSpec.Alter.ANY, spec("--alter any").alter());
        assertEquals(RouteSpec.FORBID, s.cellCost(CellClass.WATER));
        assertEquals(RouteSpec.FORBID, s.cellCost(CellClass.DOOR));
        assertEquals(0.0, s.cellCost(CellClass.GROUND));
        assertEquals(5.0, s.placeCost());
        assertEquals(7.5, s.breakPenalty());
        assertEquals(9.0, s.jumpPenalty());
        assertEquals(0.0, s.wadePenalty());
        assertTrue(s.parkour());
        assertTrue(s.climbVines());
        assertEquals(6, s.maxFallHeightNoWater());
        assertEquals(4, s.alterBudget());
    }

    @Test
    void cellsAndBoxesGoIntoThePositionTable() {
        RouteSpec s = spec("--avoid_break 1,2,3 --avoid_place 0,0,0..1,1,1 --avoid_step -5,60,-7");
        assertEquals(COST_INF, s.positions().dig(new BlockPos(1, 2, 3).asLong()));
        assertEquals(0.0, s.positions().place(new BlockPos(1, 2, 3).asLong()));
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1))) {
            assertEquals(COST_INF, s.positions().place(p.asLong()), p.toString());
        }
        assertEquals(0.0, s.positions().place(new BlockPos(2, 0, 0).asLong()));
        assertEquals(COST_INF, s.positions().stand(new BlockPos(-5, 60, -7).asLong()));
        assertTrue(s.bans().isEmpty());
    }

    @Test
    void boxCornersMayComeInAnyOrder() {
        RouteSpec s = spec("--avoid_break 3,3,3..1,1,1");
        assertEquals(COST_INF, s.positions().dig(new BlockPos(2, 2, 2).asLong()));
        assertEquals(COST_INF, s.positions().dig(new BlockPos(1, 3, 1).asLong()));
    }

    @Test
    void mistakesAreTaught() {
        assertTrue(error("--alter maybe").startsWith("expected one of none, natural, any"));
        assertTrue(error("--avoid swamp").contains("expected one of snow_layer, door, ladder, vine, water"));
        assertTrue(error("--avoid DOOR").contains("expected one of snow_layer, door, ladder, vine, water"),
                "类型名照帮助里写的小写");
        assertTrue(error("--penalty_jump -1").contains("--penalty_jump must be between 0 and 1000"));
        assertTrue(error("--penalty_jump high").startsWith("Expected double"));
        assertTrue(error("--avoid_break 1,2").startsWith("expected a cell x,y,z"));
        assertTrue(error("--avoid_step 1,two,3").contains("in whole numbers"));
        assertTrue(error("--max_fall -2").contains("--max_fall must be 0 or more"));
        assertTrue(error("--parkour yes").startsWith("Invalid bool"));

        List<String> replies = new ArrayList<>();
        ToolRegistry.get("gt_route_plan").serve("test-call",
                JsonParser.parseString("{\"avoid\": \"water\"}").getAsJsonObject(), null, replies::add);
        assertTrue(replies.get(0).contains("argument 'avoid': expected a list"), "快捷工具里一串值是数组: " + replies);
    }

    @Test
    void avoidAcceptsOnlyTypesAWalkCanKeepOutOf() {
        RouteSpec s = spec("--avoid water flowing_water");
        assertEquals(RouteSpec.FORBID, s.cellCost(CellClass.WATER));
        assertEquals(RouteSpec.FORBID, s.cellCost(CellClass.FLOWING_WATER));
        // 地面、空气、障碍不是"可以选择不走"的东西,写了就是规格写错了,报错列出能写的
        assertTrue(error("--avoid ground").contains("expected one of snow_layer, door, ladder, vine, water, "
                + "flowing_water, lava, hazard"));
        assertTrue(error("--avoid obstacle").contains("expected one of"));
        assertTrue(error("--avoid lake").contains("expected one of"));
    }

    /** 标签要等数据包绑定,无头引导下全空——标签展开只在真机验,这里只钉方块 id。 */
    @Test
    void blockIdsBecomeKindBans() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过方块 id 解析钉桩");
        RouteSpec s = spec("--avoid_break minecraft:chest oak_log --avoid_place water "
                + "--avoid_step minecraft:farmland 4,5,6");
        assertTrue(s.bans().breaking().contains(Blocks.CHEST));
        assertTrue(s.bans().breaking().contains(Blocks.OAK_LOG));
        assertFalse(s.bans().breaking().contains(Blocks.STONE));
        assertTrue(s.bans().placingInto().contains(Blocks.WATER));
        assertTrue(s.bans().standingOn().contains(Blocks.FARMLAND));
        assertEquals(COST_INF, s.positions().stand(new BlockPos(4, 5, 6).asLong()));
    }

    @Test
    void unknownBlocksAndEmptyTagsAreErrors() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过方块 id 解析钉桩");
        assertTrue(error("--avoid_break minecraft:no_such_block").contains("--avoid_break: unknown block"));
        assertTrue(error("--avoid_step #minecraft:no_such_tag").contains("--avoid_step: tag '#minecraft:no_such_tag' "
                + "has no blocks"));
    }
}
