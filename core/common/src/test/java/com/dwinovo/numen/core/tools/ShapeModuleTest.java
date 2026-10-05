package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.CoreApiFixture;
import com.dwinovo.numen.sdk.ApiTester;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置模块 {@code numen.shape} 与 {@code numen.scan} 里的值与方法,从她的入口跑:Pos 会加减、量距离、比较;形状画成 Cells,Cells 会挪、
 * 会转(方块的朝向跟着转)、会合、会减;一团会筛、会去掉几格。
 */
class ShapeModuleTest {

    @BeforeAll
    static void install() {
        CoreApiFixture.install();
    }

    /** 跑一段程序,它打印的那几行。 */
    private static String printed(String code) {
        ApiTester.Run run = ApiTester.run(null, UUID.randomUUID(), code);
        assertTrue(run.ok(), run.message());
        String message = run.message();
        return message.substring(message.indexOf("stdout:\n") + "stdout:\n".length());
    }

    @Test
    void aPosAddsMeasuresAndCompares() {
        String out = printed("""
                local p = numen.shape.pos(1, 2, 3)
                local q = p:offset(1, 0, -1)
                print(q.x, q.y, q.z, p:dist(numen.shape.pos(4, 6, 3)), (p + q).x, (q - p).z, p == numen.shape.pos(1, 2, 3))
                """);
        assertTrue(out.startsWith("2\t2\t2\t5\t3\t-1\ttrue"), out);
    }

    @Test
    void shapesAreTheCellsTheyCover() {
        String out = printed("""
                local o, c = {x = 0, y = 0, z = 0}, {x = 2, y = 2, z = 2}
                print(#numen.shape.box(o, c, "stone"), #numen.shape.box(o, c, "stone", true),
                    #numen.shape.line(o, {x = 3, y = 3, z = 3}), #numen.shape.cylinder(o, 1, 2, "stone"),
                    #numen.shape.sphere(o, 1, "glass"), numen.shape.box(o, o, "stone")[1].name,
                    numen.shape.box(o, o)[1].name == nil)
                """);
        assertTrue(out.startsWith("27\t26\t4\t18\t19\tstone\ttrue"), out);
    }

    @Test
    void cellsTurnWithTheirBlocksAndMove() {
        String out = printed("""
                local c = numen.shape.cells({{name = "oak_stairs[facing=north,half=top]", pos = {x = 1, y = 0, z = 0}},
                    {name = "oak_log[axis=x]", pos = {x = 0, y = 0, z = 2}}}):rotate(1)
                print(c[1].name, c[1].pos.x, c[1].pos.z, c[2].name, c[2].pos.x, c[2].pos.z)
                local s = c:shift(10, 1, 0)
                print(s[1].pos.x, s[1].pos.y, s:shift(numen.shape.pos(1, 1, 1))[2].pos.z)
                """);
        assertTrue(out.startsWith("oak_stairs[facing=east,half=top]\t0\t1\toak_log[axis=z]\t-2\t0\n10\t1\t1"), out);
    }

    @Test
    void cellsUniteAndSubtractByPlace() {
        String out = printed("""
                local row = numen.shape.box({x = 0, y = 0, z = 0}, {x = 2, y = 0, z = 0}, "stone")
                local u = row:union({{name = "dirt", pos = {x = 0, y = 0, z = 0}}})
                local m = row:minus({x = 1, y = 0, z = 0})
                print(#u, u[#u].name, #m, m[2].pos.x)
                """);
        assertTrue(out.startsWith("3\tdirt\t2\t2"), out);
    }

    @Test
    void aLayerReadsLikeAMapAndNamesWhatTheLegendLacks() {
        String out = printed("""
                local l = numen.shape.layer({x = 5, y = 1, z = 5}, {"#.", " <"}, {["#"] = "stone", ["<"] = "oak_stairs"})
                print(#l, l[1].pos.x, l[2].name, l[2].pos.x, l[2].pos.z)
                """);
        assertTrue(out.startsWith("2\t5\toak_stairs\t6\t6"), out);
        ApiTester.Run missing = ApiTester.run(null, UUID.randomUUID(),
                "numen.shape.layer({x = 0, y = 0, z = 0}, {\"#\"}, {})");
        assertFalse(missing.ok());
        assertTrue(missing.message().contains("which the legend does not name"), missing.message());
    }

    @Test
    void aClusterFiltersAndLosesCells() {
        String out = printed("""
                local function b(x) return {name = "minecraft:iron_ore", pos = numen.shape.pos(x, 0, 0)} end
                local c = numen.scan.cluster({b(1), b(2), b(3)})
                local f = c:filter(function(x) return x.pos.x ~= 1 end)
                local m = c:minus({x = 2, y = 0, z = 0})
                print(f.count, f.nearest.pos.x, m.count, m.blocks[2].pos.x)
                """);
        assertTrue(out.startsWith("2\t2\t2\t3"), out);
    }
}
