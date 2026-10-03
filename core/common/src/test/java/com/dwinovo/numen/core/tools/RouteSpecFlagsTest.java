package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.core.CoreScripts;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.pathing.spec.BlockBans;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Semantics.Kind;
import com.dwinovo.numen.task.TaskResult;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线规格的标志到 {@link RouteSpec} 的翻译:每个标志落到规格的哪一项,坐标格进位置表,
 * 以及每种写错都报教学式错误——写法上的错由参数类型在解析时报,意思不成立的(方块不存在)由翻译报。标志从
 * {@code command} 工具一整行进来,和她写的一样;方块 id 与标签那几条需要 MC 注册表。
 */
@Tag("mc")
class RouteSpecFlagsTest {

    /** 夹具动作的默认规格:翻译叠在它上面。 */
    private static final AtomicReference<RouteSpec> BASE = new AtomicReference<>(RouteSpec.defaults());
    private static final AtomicReference<RouteSpec> LAST = new AtomicReference<>();

    @BeforeAll
    static void boot() {
        // 程序从她的入口跑,要整份登记处(各组、库、跑脚本的工具);这一组是装好之后加的测试组
        com.dwinovo.numen.core.CoreCommandsFixture.install();
        AtomicReference<NumenApi> door = new AtomicReference<>();
        NumenPlugins.register("gt", door::set);
        door.get().registerCommands("gt_route", "Test fixture: route flags read into a spec.", g ->
                g.server("plan", "Read the route flags.", (src, args) -> {
                    LAST.set(RouteSpecFlags.parse(args, BASE.get()));
                    src.reply(TaskResult.ok("read").toJson());
                }, RouteSpecFlags.PARAMS.toArray(Param<?>[]::new))
                        .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                        .example("gt.gt_route.plan({alter = \"natural\", avoid = {\"water\"}})"));
    }

    /** 选项表交给这一组的函数,和她写的一样;跑完返回读出的规格。 */
    private static RouteSpec spec(String options) {
        CoreScripts.Run run = run(options);
        assertTrue(run.ok(), run.message());
        return LAST.get();
    }

    /** 选项写错了:程序停在那一行,返回那次调用的报错。 */
    private static String error(String options) {
        CoreScripts.Run run = run(options);
        assertFalse(run.ok(), options + " should fail");
        String message = run.message();
        String error = message.substring(message.indexOf("gt.gt_route.plan: ") + "gt.gt_route.plan: ".length());
        assertTrue(error.startsWith("bad_argument — "), "规格写错是参数错: " + error);
        return error.substring("bad_argument — ".length());
    }

    private static CoreScripts.Run run(String options) {
        LAST.set(null);
        return CoreScripts.run(UUID.randomUUID(), "gt.gt_route.plan({" + options + "})");
    }

    /** mine 的规格叠在它自己的默认上:没写的保持默认,写了的覆盖,禁令并进默认已有的。 */
    @Test
    void flagsLayOverTheCallersDefault() {
        RouteSpec base = RouteSpec.defaults().edit().alter(RouteSpec.Alter.ANY)
                .bans(new BlockBans(Set.of(Blocks.CHEST), Set.of(), Set.of())).build();
        BASE.set(base);
        try {
            assertSame(base, spec(""));
            RouteSpec kept = spec("avoid_break = {{x = 1, y = 2, z = 3}, \"minecraft:oak_log\"}");
            assertEquals(RouteSpec.Alter.ANY, kept.alter());
            assertTrue(kept.positions().forbids(Use.DIG, new BlockPos(1, 2, 3).asLong()));
            assertTrue(kept.bans().breaking().contains(Blocks.CHEST));
            assertTrue(kept.bans().breaking().contains(Blocks.OAK_LOG));
            assertEquals(RouteSpec.Alter.NATURAL, spec("alter = \"natural\"").alter());
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
        RouteSpec s = spec("alter = \"natural\", avoid = {\"water\", \"door\"}, allow = \"trigger\", penalty_place = 5, penalty_break = 7.5, penalty_jump = 9, penalty_wade = 0, parkour = true, max_fall = 6, alter_budget = 4");
        assertEquals(RouteSpec.Alter.NATURAL, s.alter());
        assertEquals(RouteSpec.Alter.ANY, spec("alter = \"any\"").alter());
        assertTrue(s.excludes(Kind.WATER));
        assertTrue(s.excludes(Kind.DOOR));
        assertFalse(s.excludes(Kind.CLIMBABLE));
        assertFalse(s.excludes(Kind.TRIGGER), "放开了的出厂排除");
        assertTrue(s.excludes(Kind.FRAGILE), "没放开的出厂排除照旧");
        assertEquals(5.0, s.placeCost());
        assertEquals(7.5, s.breakPenalty());
        assertEquals(9.0, s.jumpPenalty());
        assertEquals(0.0, s.wadePenalty());
        assertTrue(s.parkour());
        assertEquals(6, s.maxFallHeightNoWater());
        assertEquals(4, s.alterBudget());
    }

    @Test
    void cellsGoIntoThePositionTable() {
        RouteSpec s = spec("avoid_break = {x = 1, y = 2, z = 3}, avoid_place = {{x = 10, y = 64, z = 10}, "
                + "{x = 11, y = 64, z = 10}}, avoid_step = {x = -5, y = 60, z = -7}");
        assertTrue(s.positions().forbids(Use.DIG, new BlockPos(1, 2, 3).asLong()));
        assertFalse(s.positions().forbids(Use.PLACE, new BlockPos(1, 2, 3).asLong()));
        assertTrue(s.positions().forbids(Use.PLACE, new BlockPos(10, 64, 10).asLong()));
        assertTrue(s.positions().forbids(Use.PLACE, new BlockPos(11, 64, 10).asLong()));
        assertFalse(s.positions().forbids(Use.PLACE, new BlockPos(12, 64, 10).asLong()));
        assertFalse(s.positions().forbids(Use.DIG, new BlockPos(10, 64, 10).asLong()), "一串格子只进写了它的那一栏");
        assertTrue(s.positions().forbids(Use.STAND, new BlockPos(-5, 60, -7).asLong()));
        assertTrue(s.bans().isEmpty());
    }

    /** avoid 里的一格是不进入:身体不占它、脚下不踩它;格子种类照旧并列写在同一串里。 */
    @Test
    void avoidingCellsKeepsTheBodyOutOfThemAndOffThem() {
        RouteSpec s = spec("avoid = {\"water\", {x = -3, y = 63, z = -3}}");
        assertTrue(s.excludes(Kind.WATER));
        long inFarm = new BlockPos(-3, 63, -3).asLong();
        assertTrue(s.positions().forbids(Use.PASS, inFarm));
        assertTrue(s.positions().forbids(Use.STAND, inFarm));
        assertFalse(s.positions().forbids(Use.DIG, inFarm), "不进入不等于不挖");
        assertFalse(s.positions().forbids(Use.PASS, new BlockPos(-3, 64, -3).asLong()), "上面一格不在给的格里");
    }

    @Test
    void mistakesAreTaught() {
        assertTrue(error("alter = \"maybe\"").startsWith("argument 'alter': expected one of none, natural, any"));
        assertTrue(error("avoid = \"swamp\"").contains("expected one of water, flowing_water, lava, climbable, door"));
        assertTrue(error("avoid = \"DOOR\"").contains("expected one of water, flowing_water, lava, climbable, door"),
                "类型名照帮助里写的小写");
        assertTrue(error("allow = \"lava\"").contains("expected one of flowing_water, trigger, fragile"),
                "伤身的种类放不开");
        assertTrue(error("penalty_jump = -1").contains("penalty_jump must be between 0 and 1000"));
        assertTrue(error("penalty_jump = \"high\"").startsWith("argument 'penalty_jump': Expected double"));
        assertTrue(error("avoid_break = \"1,2\"").startsWith("argument 'avoid_break': expected a cell: three whole "
                + "numbers"));
        assertTrue(error("avoid_step = \"1,two,3\"").contains("three whole numbers"));
        assertTrue(error("avoid_break = \"1,2,3..4,5,6\"").contains("a box or any other stretch of cells is a list of "
                + "cells"), "盒子写法没有,报错指向一串格子");
        assertTrue(error("max_fall = -2").contains("max_fall must be 0 or more"));
        assertTrue(error("parkour = \"yes\"").contains("parkour"), "开关只收 true 或 false");
    }

    @Test
    void avoidAcceptsOnlyTypesAWalkCanKeepOutOf() {
        RouteSpec s = spec("avoid = {\"water\", \"flowing_water\"}");
        assertTrue(s.excludes(Kind.WATER));
        assertTrue(s.excludes(Kind.FLOWING_WATER));
        // 地面、空气、障碍不是"可以选择不走"的东西,写了就是规格写错了,报错列出能写的
        assertTrue(error("avoid = \"ground\"").contains("expected one of water, flowing_water, lava, climbable, door, "
                + "hazard, falling, trigger, fragile or a cell"));
        assertTrue(error("avoid = \"obstacle\"").contains("expected one of"));
        assertTrue(error("avoid = \"lake\"").contains("expected one of"));
    }

    /** 标签要等数据包绑定,无头引导下全空——标签展开只在真机验,这里只钉方块 id。 */
    @Test
    void blockIdsBecomeKindBans() {
        RouteSpec s = spec("avoid_break = {\"minecraft:chest\", \"oak_log\"}, avoid_place = \"water\", "
                + "avoid_step = {\"minecraft:farmland\", {x = 4, y = 5, z = 6}}");
        assertTrue(s.bans().breaking().contains(Blocks.CHEST));
        assertTrue(s.bans().breaking().contains(Blocks.OAK_LOG));
        assertFalse(s.bans().breaking().contains(Blocks.STONE));
        assertTrue(s.bans().placingInto().contains(Blocks.WATER));
        assertTrue(s.bans().standingOn().contains(Blocks.FARMLAND));
        assertTrue(s.positions().forbids(Use.STAND, new BlockPos(4, 5, 6).asLong()));
    }

    @Test
    void unknownBlocksAndEmptyTagsAreErrors() {
        assertTrue(error("avoid_break = \"minecraft:no_such_block\"").contains("avoid_break: unknown block"));
        assertTrue(error("avoid_step = \"#minecraft:no_such_tag\"").contains("avoid_step: tag '#minecraft:no_such_tag' "
                + "has no blocks"));
    }
}
