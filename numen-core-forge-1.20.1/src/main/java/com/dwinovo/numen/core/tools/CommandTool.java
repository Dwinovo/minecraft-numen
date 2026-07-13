package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.level.GameType;

import java.util.Map;
import java.util.function.Consumer;

public final class CommandTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String command) {}

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String description() {
        return "Execute any Minecraft command with full operator permissions. Creative mode only. "
                + "Every vanilla command is available: /fill, /setblock, /clone, /structure, "
                + "/give, /tp, /summon, /time, /weather, /gamerule, /effect, /particle, "
                + "/playsound, /title, /bossbar, /scoreboard, /worldborder, /difficulty, "
                + "/gamemode, /kill, /say, /tellraw, /msg, /team, /schedule, /place, "
                + "/locate, /loot, /enchant, /recipe, /clear, /spawnpoint, /setworldspawn, "
                + "/defaultgamemode, /seed, /help, /data, /execute, /forceload, /function, "
                + "/return, /tag, /teammsg, /teleport, /tell, /trigger, /w, /xp, "
                + "/advancement, /attribute, /ban, /ban-ip, /banlist, /debug, /deop, /list, "
                + "/op, /pardon, /pardon-ip, /perf, /publish, /save-all, /save-off, /save-on, "
                + "/spectate, /spreadplayers, /stopsound, /whitelist."
                + " Returns the command output.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("command", "The Minecraft command, e.g. \"fill 100 64 200 110 70 210 minecraft:stone\"")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        if (companion.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
            reply.accept(TaskResult.fail("run_command only works in creative mode").toJson());
            return;
        }

        Args a = GSON.fromJson(args, Args.class);
        String cmd = a.command().trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);

        var server = companion.level.getServer();
        if (server == null) {
            reply.accept(TaskResult.fail("Server not available").toJson());
            return;
        }

        CommandSourceStack source = companion.createCommandSourceStack()
                .withPermission(4)
                .withSuppressedOutput();

        int result = server.getCommands().performPrefixedCommand(source, cmd);
        reply.accept(TaskResult.ok("Command returned " + result + ": /" + cmd).toJson());
    }
}
