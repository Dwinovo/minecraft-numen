package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.NumenApiFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 蒙版的写法照 WorldEdit:每一种写法在一个小世界里过哪几格、不过哪几格。世界是 {@link FakeBlocks}(地面在 y = 0 以下是石头,上面空气),上面摆了几块:
 * x=1 泥土,x=2 朝北的上半楼梯,x=3 台阶,x=4 火把,x=10 到 12 的三层一格宽实心石块。
 */
class ShapeMasksTest {

    private static final FakeBlocks WORLD = FakeBlocks.flat(-1)
            .put(0, 0, 0, "stone").put(1, 0, 0, "dirt").put(2, 0, 0, "oak_stairs[facing=north,half=top]")
            .put(3, 0, 0, "oak_slab[type=bottom]").put(4, 0, 0, "torch")
            .fill(10, 0, 0, 12, 2, 2, "stone");

    @BeforeAll
    static void install() {
        NumenApiFixture.install();
    }

    private static boolean test(String mask, int x, int y, int z) {
        return ShapeMasks.parse(mask).test(WORLD, x, y, z);
    }

    @Test
    void blocksAreListedAndAPropertyLeftOutMatchesAnyValue() {
        assertTrue(test("stone", 0, 0, 0));
        assertFalse(test("stone", 1, 0, 0));
        assertTrue(test("stone,dirt", 1, 0, 0));
        assertTrue(test("oak_stairs", 2, 0, 0));
        assertTrue(test("oak_stairs[half=top]", 2, 0, 0));
        assertFalse(test("oak_stairs[half=bottom]", 2, 0, 0));
        assertTrue(test("air", 20, 5, 5));
        assertFalse(test("air", 0, 0, 0));
        // the comma inside a state list is not a list separator
        assertTrue(test("dirt,oak_stairs[facing=north,half=top]", 2, 0, 0));
    }

    @Test
    void theNamedMasksAreVanillasOwnJudgements() {
        assertTrue(test("#existing", 0, 0, 0));
        assertFalse(test("#existing", 20, 5, 5));
        assertTrue(test("#air", 20, 5, 5));
        // a torch does not stop movement, a stone does; a slab is a block but not a full cube
        assertTrue(test("#solid", 0, 0, 0));
        assertFalse(test("#solid", 4, 0, 0));
        assertTrue(test("#fullcube", 0, 0, 0));
        assertFalse(test("#fullcube", 3, 0, 0));
        assertFalse(test("#fullcube", 20, 5, 5));
    }

    @Test
    void surfaceIsABlockWithAirBesideIt() {
        assertTrue(test("#surface", 10, 0, 0), "a corner of the block");
        assertTrue(test("#exposed", 11, 1, 0), "a face of the block is beside air");
        assertFalse(test("#surface", 11, 1, 1), "the middle of a 3 x 3 x 3 block has stone all round");
        assertFalse(test("#surface", 20, 5, 5), "air itself is not a surface");
    }

    @Test
    void underOverAndBesideLookAtTheNeighbours() {
        assertTrue(test(">stone", 0, 1, 0), "the cell over a stone has a stone under it");
        assertTrue(test(">", 0, 1, 0), "a bare > means an existing block under it");
        assertFalse(test(">stone", 1, 1, 0));
        assertTrue(test("<stone", 0, -1, 0), "the cell under a stone has a stone over it");
        assertFalse(test("<stone", 1, -1, 0));
        assertTrue(test("<dirt", 1, -1, 0));
        assertTrue(test("~dirt", 0, 0, 0), "the stone has dirt beside it");
        assertFalse(test("~dirt", 3, 0, 0));
        assertTrue(test("~", 3, 0, 0), "a bare ~ means any existing block beside it");
    }

    @Test
    void notAndSeveralWordsAreAndEveryOneMustHold() {
        assertTrue(test("!air", 0, 0, 0));
        assertFalse(test("!air", 20, 5, 5));
        assertTrue(test("!stone", 1, 0, 0));
        assertTrue(test("#existing !dirt", 0, 0, 0));
        assertFalse(test("#existing !dirt", 1, 0, 0));
        assertTrue(test("stone,dirt #surface", 0, 0, 0));
    }

    @Test
    void statesAreMatchedOnTheBlocksThatHaveThem() {
        assertTrue(test("^[half=top]", 2, 0, 0));
        assertFalse(test("^[half=bottom]", 2, 0, 0));
        assertTrue(test("^[half=top]", 0, 0, 0), "stone has no half: passed over");
        assertFalse(test("^=[half=top]", 0, 0, 0), "but the strict form needs the property");
        assertTrue(test("^=[half=top,facing=north]", 2, 0, 0));
    }

    @Test
    void aChanceIsAPercentage() {
        for (int i = 0; i < 200; i++) {
            assertFalse(test("%0", 0, 0, 0));
        }
        for (int i = 0; i < 50; i++) {
            assertTrue(test("%100", 0, 0, 0));
        }
        int hits = 0;
        for (int i = 0; i < 2000; i++) {
            hits += test("%25", 0, 0, 0) ? 1 : 0;
        }
        // 500 expected, standard deviation about 19: five of them either side
        assertTrue(Math.abs(hits - 500) < 100, "" + hits);
    }

    @Test
    void whatDoesNotReadSaysWhichWord() {
        IllegalArgumentException block = assertThrows(IllegalArgumentException.class, () -> ShapeMasks.parse("stone,nope"));
        assertTrue(block.getMessage().contains("nope") && block.getMessage().contains("is not a mask"), block.getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ShapeMasks.parse("=x + 1")).getMessage().contains("expression"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ShapeMasks.parse("##no_such_tag")).getMessage()
                .contains("no block tag"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ShapeMasks.parse("%x")).getMessage().contains("no chance"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ShapeMasks.parse("!")).getMessage().contains("nothing after"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ShapeMasks.parse("   ")).getMessage().contains("no mask"));
    }
}
