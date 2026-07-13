package com.dwinovo.numen.entity;

import com.dwinovo.numen.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.platform.Services;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * The unified server-side {@code /numen} command tree, modelled on Carpet's
 * {@code /player} verbs. Lives entirely on the server so it never collides with
 * the client command dispatcher; the two inherently client-local verbs
 * ({@code settings}, {@code reset}) act on the caller's own client by firing a
 * {@link ClientUiActionPayload} back at them.
 *
 * <pre>
 *   /numen player summon &lt;name&gt;    summon the named companion (idempotent — reuses an existing one)
 *   /numen player despawn &lt;name&gt;   permanently dismiss the named companion (gone for good)
 *   /numen settings                  open the settings GUI on the caller's client
 *   /numen reset                     clear the caller's conversation loops
 * </pre>
 */
@com.dwinovo.numen.api.Internal
public final class NumenCommands {

    private NumenCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("numen")
                .then(Commands.literal("player")
                        .then(Commands.literal("summon")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("despawn")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> despawn(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("gamemode")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.literal("survival")
                                                .executes(ctx -> gamemode(ctx, StringArgumentType.getString(ctx, "name"), GameType.SURVIVAL)))
                                        .then(Commands.literal("creative")
                                                .executes(ctx -> gamemode(ctx, StringArgumentType.getString(ctx, "name"), GameType.CREATIVE))))))
                .then(Commands.literal("settings")
                        .executes(ctx -> clientAction(ctx, ClientUiActionPayload.Action.OPEN_SETTINGS)))
                .then(Commands.literal("reset")
                        .executes(ctx -> clientAction(ctx, ClientUiActionPayload.Action.RESET_LOOPS))));
    }

    private static int summon(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) owner.level;
        NumenPlayer body = Companions.summon(
                level.getServer(), owner.getUUID(), name, level, owner.position());
        // Push the updated roster so the owner's G panel can reach the new companion.
        Companions.syncRosterToOwner(level.getServer(), owner);
        ctx.getSource().sendSuccess(() ->
                Component.nullToEmpty("Summoned companion '" + name + "' (" + body.getUUID() + ")"), false);
        return 1;
    }

    private static int despawn(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        var server = owner.level.getServer();
        // Permanent dismissal: removes the live body AND its registry entry (and any same-name
        // duplicates), so it does NOT come back on the next login. NOT dormancy.
        int dismissed = Companions.dismissByName(server, owner.getUUID(), name);
        if (dismissed == 0) {
            ctx.getSource().sendFailure(
                    Component.nullToEmpty("No companion of yours named '" + name + "'"));
            return 0;
        }
        Companions.syncRosterToOwner(server, owner);
        ctx.getSource().sendSuccess(() -> Component.nullToEmpty(
                "Dismissed companion '" + name + "' — gone for good"
                        + (dismissed > 1 ? " (cleaned up " + dismissed + " duplicates)" : "")), false);
        return dismissed;
    }

    private static int gamemode(CommandContext<CommandSourceStack> ctx, String name, GameType gameType)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        var server = owner.level.getServer();
        NumenPlayer found = null;
        for (var e : CompanionRegistry.get(server).ownedBy(owner.getUUID())) {
            if (e.getValue().name().equals(name)) {
                found = NumenPlayer.findByUuid(server, e.getKey());
                break;
            }
        }
        if (found == null) {
            ctx.getSource().sendFailure(
                    Component.nullToEmpty("No companion of yours named '" + name + "'"));
            return 0;
        }
        found.setGameMode(gameType);
        if (gameType == GameType.CREATIVE) {
            found.getAbilities().mayfly = true;
            found.getAbilities().instabuild = true;
        } else {
            found.getAbilities().mayfly = false;
            found.getAbilities().flying = false;
            found.getAbilities().instabuild = false;
        }
        found.onUpdateAbilities();
        String modeName = gameType == GameType.CREATIVE ? "创造" : "生存";
        ctx.getSource().sendSuccess(() ->
                Component.nullToEmpty("已将 '" + name + "' 的游戏模式设为 " + modeName), false);
        return 1;
    }

    private static int clientAction(CommandContext<CommandSourceStack> ctx,
                                    ClientUiActionPayload.Action action)
            throws CommandSyntaxException {
        ServerPlayer caller = ctx.getSource().getPlayerOrException();
        Services.NETWORK.sendToPlayer(caller, ClientUiActionPayload.ID, new ClientUiActionPayload(action));
        return 1;
    }
}
