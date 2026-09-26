package com.dwinovo.numen.core.build;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.NumenCli;
import com.dwinovo.numen.core.CoreCommandsFixture;
import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code build show <设计> --layer <y>} 画的那张俯视图:每一格是建成之后的最终方块(后写覆盖先写,门的上半、床头照建成的样子
 * 补上),字符网格与图例是 {@code build layer} 的写法,行多了按输出预算分页。
 */
class SliceTest {

    @BeforeAll
    static void install() {
        CoreCommandsFixture.install();
    }

    private static Design design(String... steps) {
        return Design.parse("house", "# Numen design house\n" + String.join("\n", steps) + "\n");
    }

    private static String draw(Design design, int y) {
        return page(design, y, "build show house --layer " + y);
    }

    private static String page(Design design, int y, String line) {
        CommandArgs args = NumenCli.read(line).args();
        TaskResult result = Slice.of("design house", design.drawn().targets(), y, "build show house --layer " + y)
                .result(args);
        assertTrue(result.success(), result.message());
        return result.message();
    }

    /** 后写覆盖先写:中间那一格先铺石头、后改木板,画出来是木板。 */
    @Test
    void aLevelShowsTheLastBlockWrittenToEachCell() {
        Design house = design("build layer 0 0 0 ### ### ### --block stone", "build set oak_planks 1 0 1");
        assertEquals("""
                design house at y=0 (it spans y 0), seen from above: x 0..2 left to right (east), z 0..2 top to \
                bottom (south); the x row gives each column's last digit; . = nothing here.
                x    012
                z 0  sss
                z 1  sos
                z 2  sss
                legend: s=stone o=oak_planks""", draw(house, 0));
    }

    /** 和 build layer 同一个约定:'.' 是什么都没画;一格写成空气(挖掉)是图例里的一种,和没画分得开。 */
    @Test
    void nothingDrawnIsADotAndAnAirCellIsNamed() {
        Design house = design("build layer 0 0 0 #.# --block stone", "build set air 0 0 1");
        String map = draw(house, 0);
        assertTrue(map.contains("\nz 0  s.s\nz 1  a..\n"), map);
        assertTrue(map.endsWith("legend: s=stone a=air"), map);
    }

    /** 门的上半与床头不在施工图里,建成后在;看的是建成后这一层的样子。 */
    @Test
    void theUpperHalfOfADoorAndTheHeadOfABedAreDrawnWhereTheyWillStand() {
        Design house = design("build set oak_door[facing=south] 0 0 0", "build set red_bed[facing=east] 2 0 0");
        String ground = draw(house, 0);
        assertTrue(ground.contains("\nz 0  o.rR\n"), ground);
        assertTrue(ground.contains("r=red_bed[facing=east,occupied=false,part=foot]"), ground);
        assertTrue(ground.contains("R=red_bed[facing=east,occupied=false,part=head]"), ground);
        String above = draw(house, 1);
        assertTrue(above.contains("\nz 0  o...\n"), above);
        assertTrue(above.contains("o=oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]"), above);
    }

    /** 每一层用同一个框:上面一层只有一格,也画出整份的 x、z 范围,两层叠得上。 */
    @Test
    void everyLevelIsDrawnInTheSameFrame() {
        Design house = design("build layer 0 0 0 #### #### --block stone", "build set torch 3 1 1");
        String upper = draw(house, 1);
        assertTrue(upper.contains("x 0..3 left to right (east), z 0..1 top to bottom (south)"), upper);
        assertTrue(upper.contains("\nz 0  ....\nz 1  ...t\n"), upper);
    }

    @Test
    void aLevelOutsideTheDesignSaysWhereItSpans() {
        Design house = design("build layer 0 0 0 ## --block stone --up_to 2");
        assertEquals("design house has nothing at y=7; it spans y 0..2.", draw(house, 7));
    }

    /** 一张大图按输出预算分页:第一页说一共多少行、这是哪一段、下一页怎么取;第二页从停下的那一行接着。 */
    @Test
    void aSliceOverTheOutputBudgetComesAPageAtATime() {
        Design wide = design("build set stone 0 0 0", "build set stone 999 0 999");
        String first = draw(wide, 0);
        Matcher shown = Pattern.compile("\n\\[Showing 1-(\\d+) of 1000\\. Use build show house --layer 0 --page 2 "
                + "to continue\\.]\nlegend: s=stone$").matcher(first);
        assertTrue(shown.find(), first.substring(first.length() - 200));
        int rows = Integer.parseInt(shown.group(1));
        assertTrue(first.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < Listing.MAX_BYTES + 200);
        assertFalse(first.contains(String.format("\nz %3d  ", rows)), "下一行不在这一页");

        String second = page(wide, 0, "build show house --layer 0 --page 2");
        assertTrue(second.contains(String.format("\nx      %s\nz %3d  ", "0123456789".repeat(100), rows)),
                "第二页从停下的那一行接着,x 标尺照旧");
    }

    @Test
    void theLegendReadsAsTheLegendOfBuildLayer() {
        Design house = design("build layer 0 0 0 <<< ... >>> --legend <=oak_stairs[facing=south] "
                + ">=oak_stairs[facing=north]");
        String map = draw(house, 0);
        String legend = map.substring(map.lastIndexOf("\nlegend: ") + "\nlegend: ".length());
        // 图例的每一项就是 build layer 的 --legend 一项:抄回去画出来是同一层
        Design copied = design("build layer 0 0 0 " + String.join(" ", rows(map)) + " --legend " + legend);
        assertEquals(map, draw(copied, 0));
    }

    /** 图上的格子行(去掉行首的 z 标)。 */
    private static List<String> rows(String map) {
        return map.lines().filter(l -> l.startsWith("z ")).map(l -> l.substring(5)).toList();
    }
}
