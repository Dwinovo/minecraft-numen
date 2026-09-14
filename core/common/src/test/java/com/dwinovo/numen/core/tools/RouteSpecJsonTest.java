package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.pathing.spec.CellClass;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 工具面 spec 对象到 {@link RouteSpec} 的翻译:每个旋钮落到规格的哪一项,坐标格与坐标盒进位置表,
 * 以及每种写错都报教学式错误。方块 id 与标签那两条需要 MC 注册表,无头引导失败时跳过。
 */
@Tag("mc")
class RouteSpecJsonTest {

    private static boolean booted;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static String error(String text) {
        return assertThrows(IllegalArgumentException.class, () -> RouteSpecJson.parse(json(text))).getMessage();
    }

    @Test
    void nullOrEmptyIsTheFactorySpec() {
        assertSame(RouteSpec.defaults(), RouteSpecJson.parse(null));
        RouteSpec s = RouteSpecJson.parse(json("{}"));
        assertEquals(RouteSpec.Alter.NONE, s.alter());
        assertTrue(s.positions().isEmpty());
        assertTrue(s.bans().isEmpty());
    }

    @Test
    void everyKnobLandsOnItsField() {
        RouteSpec s = RouteSpecJson.parse(json("""
                {"alter":"natural","avoid":["water","DOOR"],
                 "penalties":{"place":5,"break":7.5,"jump":9,"wade":0},
                 "parkour":true,"climb_vines":true,"max_fall":6,"max_alterations":4}"""));
        assertEquals(RouteSpec.Alter.NATURAL, s.alter());
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
        assertEquals(4, s.maxAlterations());
    }

    @Test
    void cellsAndBoxesGoIntoThePositionTable() {
        RouteSpec s = RouteSpecJson.parse(json("""
                {"avoid_break":["1,2,3"],"avoid_place":["0,0,0..1,1,1"],"avoid_step":["-5,60,-7"]}"""));
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
        RouteSpec s = RouteSpecJson.parse(json("{\"avoid_break\":[\"3,3,3..1,1,1\"]}"));
        assertEquals(COST_INF, s.positions().dig(new BlockPos(2, 2, 2).asLong()));
        assertEquals(COST_INF, s.positions().dig(new BlockPos(1, 3, 1).asLong()));
    }

    @Test
    void mistakesAreTaught() {
        assertTrue(error("{\"alter\":\"maybe\"}").contains("'none' or 'natural'"));
        assertTrue(error("{\"avoid\":[\"swamp\"]}").contains("unknown cell type 'swamp'"));
        assertTrue(error("{\"avoid\":\"water\"}").contains("array of strings"));
        assertTrue(error("{\"penalties\":{\"jump\":-1}}").contains("between 0 and"));
        assertTrue(error("{\"penalties\":{\"jump\":\"high\"}}").contains("must be a number"));
        assertTrue(error("{\"penalties\":3}").contains("must be an object"));
        assertTrue(error("{\"avoid_break\":[\"1,2\"]}").contains("'x,y,z'"));
        assertTrue(error("{\"avoid_step\":[\"1,two,3\"]}").contains("integers"));
        assertTrue(error("{\"max_fall\":-2}").contains("0 or more"));
        assertTrue(error("{\"parkour\":\"yes\"}").contains("true or false"));
    }

    /** 标签要等数据包绑定,无头引导下全空——标签展开只在真机验,这里只钉方块 id。 */
    @Test
    void blockIdsBecomeKindBans() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过方块 id 解析钉桩");
        RouteSpec s = RouteSpecJson.parse(json("""
                {"avoid_break":["minecraft:chest","oak_log"],
                 "avoid_place":["water"],"avoid_step":["minecraft:farmland","4,5,6"]}"""));
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
        assertTrue(error("{\"avoid_break\":[\"minecraft:no_such_block\"]}").contains("unknown block"));
        assertTrue(error("{\"avoid_step\":[\"#minecraft:no_such_tag\"]}").contains("has no blocks"));
    }
}
