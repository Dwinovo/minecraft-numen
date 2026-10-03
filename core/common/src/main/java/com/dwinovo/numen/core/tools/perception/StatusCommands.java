package com.dwinovo.numen.core.tools.perception;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.tools.PerceptionOps;

/**
 * {@code status}:她此刻的身体、主人、世界。三个动作都在服务端当场读、当场回,不占身体、不动世界,回执是一份 JSON。
 *
 * <p>{@code self} 与 {@code owner} 是每做一个决定前都要看的,提升为快捷工具({@code status_self} /
 * {@code status_owner});{@code world} 用得少,只留命令。
 */
public final class StatusCommands {

    private static final PerceptionOps OPS = new PerceptionOps();

    private StatusCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands("status", "Your body, your owner and the world right now.", StatusCommands::actions);
    }

    private static void actions(CommandGroup status) {
        status.server("self", "Your body: health, hunger, position, biome, what is in your hands and on you, "
                        + "movement state.",
                        StatusCommands::self)
                .example("status.self()")
                .note("Instant and read-only: name, game mode, health, hunger and saturation, position, dimension, "
                        + "biome, the structures you stand in, what is in your hands, what you wear and what mods "
                        + "report about your body, movement state.")
                .note("It does not list your backpack: what you carry is in front of you every turn; `use.gui()` "
                        + "shows exact slots.")
                .seeAlso("status owner", "status world");
        status.server("owner", "Your owner: online or not, health, hunger, position, distance from you, held items.",
                        StatusCommands::owner)
                .example("status.owner()")
                .note("Instant and read-only. An offline owner comes back as online:false.")
                .seeAlso("status self");
        status.server("world", "The world: dimension, game time, whether it is bright or dark outside, weather.",
                        StatusCommands::world)
                .example("status.world()")
                .note("Instant and read-only. Darkness and weather matter for mobs, combat and sailing.")
                .seeAlso("status self");
    }

    private static void self(ServerSource src, CommandArgs args) {
        src.reply(OPS.getSelfStatus(src.companion()));
    }

    private static void owner(ServerSource src, CommandArgs args) {
        src.reply(OPS.getOwnerStatus(src.companion()));
    }

    private static void world(ServerSource src, CommandArgs args) {
        src.reply(OPS.getWorldInfo(src.companion()));
    }
}
