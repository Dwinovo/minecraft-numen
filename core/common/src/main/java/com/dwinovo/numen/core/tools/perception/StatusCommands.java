package com.dwinovo.numen.core.tools.perception;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.tools.PerceptionOps;

/**
 * {@code status}:她此刻的身体、主人、世界。三个动作都在服务端当场读、当场回,不占身体、不动世界,回执是一份 JSON。
 *
 * <p>{@code self} 与 {@code owner} 是每做一个决定前都要看的,提升回原来的工具名({@code get_self_status} /
 * {@code get_owner_status});{@code world} 用得少,只留命令。
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
                .example("status self")
                .note("Instant and read-only.")
                .note("It does not list your backpack: what you carry is in front of you every turn.")
                .seeAlso("status owner", "status world")
                // 本能名册不在这里:它在系统提示的 <instincts> 里,每次请求都在,不必再随这条描述发一遍。
                .promote("get_self_status", "Read your body's condition in one call: name, game mode, HP / max HP, "
                        + "hunger / saturation, position, dimension, biome, the structures you are "
                        + "standing in, what is in your hands, what you wear (<worn>) and what mods report "
                        + "about your body, and movement "
                        + "state. ALWAYS call this before "
                        + "combat or planning decisions. It does NOT list your backpack — what you carry "
                        + "is already in front of you every turn; run use gui when exact slots matter. "
                        + "No arguments.");
        status.server("owner", "Your owner: online or not, health, hunger, position, distance from you, held items.",
                        StatusCommands::owner)
                .example("status owner")
                .note("Instant and read-only. An offline owner comes back as online:false.")
                .seeAlso("status self")
                .promote("get_owner_status", "Read your owner's current status: name, online state, HP, hunger, "
                        + "position, distance from you, and held item. Call before any 'follow', 'protect', or "
                        + "'rendezvous' decision. If the owner is offline the call returns online:false "
                        + "— default to autonomous mode until they return. No arguments.");
        status.server("world", "The world: dimension, game time, whether it is bright or dark outside, weather.",
                        StatusCommands::world)
                .example("status world")
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
