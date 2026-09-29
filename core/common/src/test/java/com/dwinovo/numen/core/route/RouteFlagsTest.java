package com.dwinovo.numen.core.route;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.NumenCli;
import com.dwinovo.numen.core.CoreCommandsFixture;
import com.dwinovo.numen.core.task.move.Destination;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Semantics.Kind;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 路线上存的规格:写下的标志存成命令行上的一截,读回来还是那些标志;改的时候一个标志换一个;一段的标志叠在整条的上面。 */
class RouteFlagsTest {

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
        String merged = RouteFlags.merged("home", "--alter natural --avoid water", line("--avoid lava hazard"));
        assertEquals("--alter natural --avoid lava hazard", merged);
        assertThrows(IllegalArgumentException.class,
                () -> RouteFlags.merged("home", "", line("--avoid_break minecraft:no_such_block")));
    }

    @Test
    void aLegsFlagsAddToTheWholeRoutes() {
        ResourceLocation overworld = ResourceLocation.withDefaultNamespace("overworld");
        Destination.Stop to = new Destination.Stop(1, 64, 1, Destination.Arrive.AT, null);
        Itinerary route = Itinerary.of("home", overworld, to, "--alter natural").via(to, 1)
                .withLegFlags(2, "--avoid water");
        RouteSpec first = RouteFlags.spec(route, 0);
        RouteSpec second = RouteFlags.spec(route, 1);
        assertEquals(RouteSpec.Alter.NATURAL, first.alter());
        assertEquals(RouteSpec.Alter.NATURAL, second.alter());
        assertFalse(first.excludes(Kind.WATER));
        assertTrue(second.excludes(Kind.WATER));
    }
}
