package com.dwinovo.numen.core.tools.perception;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.tools.PerceptionOps;

/**
 * {@code status}:她此刻的身体、主人、世界。三个动作都在服务端当场读、当场回,不占身体、不动世界;脚本拿到的是那份读数,位置是
 * Pos,原样就能交给要一格的参数({@code move.goto_(status.owner().pos)})。
 */
public final class StatusCommands {

    private static final PerceptionOps OPS = new PerceptionOps();
    /** 手里拿着的一样。 */
    private static final ScriptType HELD = ScriptType.table(ScriptType.field("item", ScriptType.STRING, null),
            ScriptType.field("count", ScriptType.INTEGER, null));

    private StatusCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands("status", "Your body, your owner and the world right now.", StatusCommands::actions);
    }

    private static void actions(CommandGroup status) {
        status.server("self", "Your body: health, hunger, position, biome, what is in your hands and on you, "
                        + "movement state.",
                        StatusCommands::self)
                .returns(ScriptType.table(
                        ScriptType.field("id", ScriptType.INTEGER, "Your own entity id."),
                        ScriptType.field("name", ScriptType.STRING, null),
                        ScriptType.field("pos", Shapes.POS.type(), "Where you are (decimals)."),
                        ScriptType.field("hp", ScriptType.NUMBER, null),
                        ScriptType.field("max_hp", ScriptType.NUMBER, null),
                        ScriptType.field("hunger", ScriptType.INTEGER, "0-20."),
                        ScriptType.field("saturation", ScriptType.NUMBER, null),
                        ScriptType.field("game_mode", ScriptType.STRING, null),
                        ScriptType.field("dimension", ScriptType.STRING, null),
                        ScriptType.field("biome", ScriptType.STRING, null),
                        ScriptType.field("structures", ScriptType.listOf(ScriptType.STRING), "Structures you stand in."),
                        ScriptType.field("hands", ScriptType.table(
                                ScriptType.optional("mainhand", HELD, null), ScriptType.optional("offhand", HELD, null)),
                                "What is in your hands."),
                        ScriptType.field("backpack_slots", ScriptType.table(
                                ScriptType.field("used", ScriptType.INTEGER, null),
                                ScriptType.field("total", ScriptType.INTEGER, null)), null),
                        ScriptType.field("on_ground", ScriptType.BOOLEAN, null),
                        ScriptType.field("in_water", ScriptType.BOOLEAN, null),
                        ScriptType.field("in_lava", ScriptType.BOOLEAN, null),
                        ScriptType.field("air", ScriptType.INTEGER, "Breath left, in ticks."),
                        ScriptType.field("max_air", ScriptType.INTEGER, null),
                        ScriptType.optional("body_state", ScriptType.STRING, "What you wear and what mods report "
                                + "about your body.")))
                .example("local me = status.self()\nprint(me.pos.x, me.pos.y, me.pos.z, me.hp)")
                .note("Instant and read-only: name, game mode, health, hunger and saturation, position, dimension, "
                        + "biome, the structures you stand in, what is in your hands, what you wear and what mods "
                        + "report about your body, movement state.")
                .note("It does not list your backpack: what you carry is in front of you every turn; `use.gui()` "
                        + "shows exact slots.")
                .seeAlso("status owner", "status world");
        status.server("owner", "Your owner: online or not, health, hunger, position, distance from you, held items.",
                        StatusCommands::owner)
                .returns(ScriptType.table(
                        ScriptType.field("online", ScriptType.BOOLEAN, null),
                        ScriptType.optional("id", ScriptType.INTEGER, "Their entity id."),
                        ScriptType.optional("name", ScriptType.STRING, null),
                        ScriptType.optional("pos", Shapes.POS.type(), "Where they are (decimals), in their dimension."),
                        ScriptType.optional("distance", ScriptType.NUMBER, "Blocks from you, in the same dimension."),
                        ScriptType.optional("same_dimension", ScriptType.BOOLEAN, null),
                        ScriptType.optional("dimension", ScriptType.STRING, null),
                        ScriptType.optional("hp", ScriptType.NUMBER, null),
                        ScriptType.optional("max_hp", ScriptType.NUMBER, null),
                        ScriptType.optional("hunger", ScriptType.INTEGER, null),
                        ScriptType.optional("saturation", ScriptType.NUMBER, null),
                        ScriptType.optional("main_hand", ScriptType.STRING, null),
                        ScriptType.optional("off_hand", ScriptType.STRING, null)))
                .example("local owner = status.owner()\nif owner.online then print(owner.pos.x, owner.pos.z) end")
                .note("Instant and read-only. An offline owner comes back as online:false.")
                .seeAlso("status self");
        status.server("world", "The world: dimension, game time, whether it is bright or dark outside, weather.",
                        StatusCommands::world)
                .returns(ScriptType.table(
                        ScriptType.field("dimension", ScriptType.STRING, null),
                        ScriptType.field("game_time", ScriptType.INTEGER, null),
                        ScriptType.field("is_bright_outside", ScriptType.BOOLEAN, null),
                        ScriptType.field("is_dark_outside", ScriptType.BOOLEAN, null),
                        ScriptType.field("weather", ScriptType.choice(java.util.List.of("clear", "rain", "thunder")),
                                null)))
                .example("status.world()")
                .note("Instant and read-only. Darkness and weather matter for mobs, combat and sailing.")
                .seeAlso("status self");
    }

    private static void self(ServerSource src, CommandArgs args) {
        src.reply(OPS.getSelfStatus(src.companion()).toJson());
    }

    private static void owner(ServerSource src, CommandArgs args) {
        src.reply(OPS.getOwnerStatus(src.companion()).toJson());
    }

    private static void world(ServerSource src, CommandArgs args) {
        src.reply(OPS.getWorldInfo(src.companion()).toJson());
    }
}
