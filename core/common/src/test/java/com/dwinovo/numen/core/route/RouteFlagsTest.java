package com.dwinovo.numen.core.route;

import java.util.Map;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.NumenCli;
import com.dwinovo.numen.core.CoreCommandsFixture;
import com.dwinovo.numen.core.nav.NamedAreas;
import com.dwinovo.numen.core.task.move.Destination;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Semantics.Kind;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线上存的规格:写下的标志存成命令行上的一截,读回来还是那些标志;改的时候一个标志换一个;一段的标志叠在整条的上面;
 * 点名的区域存名字,改的时候不在就拒,翻成规格时按那一刻的区域。
 */
class RouteFlagsTest {

    private static final NamedAreas NONE = new NamedAreas(Level.OVERWORLD, Map.of());

    @BeforeAll
    static void install() {
        CoreCommandsFixture.install();
    }

    private static CommandArgs line(String flags) {
        return NumenCli.read("route spec home " + flags).args();
    }

    @Test
    void writtenFlagsReadBackAndEachGivenFlagReplacesItsOldValue() {
        assertEquals("--alter natural --avoid water", RouteFlags.written(line("--avoid water --alter natural")));
        assertEquals("", RouteFlags.written(NumenCli.read("route spec home").args()));
        String merged = RouteFlags.merged("home", "--alter natural --avoid water", line("--avoid lava hazard"),
                NONE);
        assertEquals("--alter natural --avoid lava hazard", merged);
        assertThrows(IllegalArgumentException.class,
                () -> RouteFlags.merged("home", "", line("--avoid_break minecraft:no_such_block"), NONE));
    }

    /** 标志里的区域存的是名字:改的时候区域得在;存下以后区域换了格子,翻出来的规格跟着换;删了,翻的时候说是哪块不在。 */
    @Test
    void areasInTheFlagsAreKeptByName() {
        Area small = Area.of(Level.OVERWORLD, Area.Kind.BOX, Cells.box(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1)));
        Area moved = Area.of(Level.OVERWORLD, Area.Kind.BOX, Cells.box(new BlockPos(9, 64, 9), new BlockPos(9, 64, 9)));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> RouteFlags.merged("home", "", line("--avoid_break area:house"), NONE));
        assertTrue(refused.getMessage().contains("there is no area named house"), refused.getMessage());
        String flags = RouteFlags.merged("home", "", line("--avoid_break area:house"),
                new NamedAreas(Level.OVERWORLD, Map.of("house", small)));
        assertEquals("--avoid-break area:house", flags, "存下的标志按命令行的写法写回:短横线");

        ResourceLocation overworld = ResourceLocation.withDefaultNamespace("overworld");
        Itinerary route = Itinerary.of("home", overworld, new Destination.Stop(20, 64, 20, Destination.Arrive.AT,
                null), flags);
        RouteSpec now = RouteFlags.spec(route, 0, new NamedAreas(Level.OVERWORLD, Map.of("house", moved)));
        assertTrue(now.positions().forbids(Use.DIG, new BlockPos(9, 64, 9).asLong()), "按此刻的区域");
        assertFalse(now.positions().forbids(Use.DIG, new BlockPos(0, 64, 0).asLong()));
        IllegalArgumentException gone = assertThrows(IllegalArgumentException.class,
                () -> RouteFlags.spec(route, 0, NONE));
        assertTrue(gone.getMessage().contains("avoid_break area:house: there is no area named house"),
                gone.getMessage());
    }

    @Test
    void aLegsFlagsAddToTheWholeRoutes() {
        ResourceLocation overworld = ResourceLocation.withDefaultNamespace("overworld");
        Destination.Stop to = new Destination.Stop(1, 64, 1, Destination.Arrive.AT, null);
        Itinerary route = Itinerary.of("home", overworld, to, "--alter natural").via(to, 1)
                .withLegFlags(2, "--avoid water");
        RouteSpec first = RouteFlags.spec(route, 0, NONE);
        RouteSpec second = RouteFlags.spec(route, 1, NONE);
        assertEquals(RouteSpec.Alter.NATURAL, first.alter());
        assertEquals(RouteSpec.Alter.NATURAL, second.alter());
        assertFalse(first.excludes(Kind.WATER));
        assertTrue(second.excludes(Kind.WATER));
    }
}
