package com.dwinovo.numen.entity;

import com.dwinovo.numen.network.payload.ClientGoalCommandPayload;
import com.dwinovo.numen.platform.Services;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side {@code /goal} bridge. The goal state lives in each companion's
 * client-side agent loop, so this command tree resolves the owner's companion
 * by name and ships the chat-style command back to the caller's client:
 *
 * <pre>
 *   /goal                         status for every owned companion
 *   /goal help                    help for every owned companion
 *   /goal &lt;companion&gt; &lt;command...&gt;  run a goal command on one companion
 * </pre>
 */
@com.dwinovo.numen.api.Internal
public final class GoalCommands {

    private GoalCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("goal")
                .then(Commands.literal("help")
                        .executes(ctx -> sendToOwned(ctx, "/goal help")))
                .then(Commands.argument("companion", StringArgumentType.word())
                        .suggests(GoalCommands::suggestCompanions)
                        .executes(GoalCommands::sendDirect)
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .executes(GoalCommands::sendToNamed)))
                .executes(ctx -> sendToOwned(ctx, "/goal status")));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestCompanions(CommandContext<CommandSourceStack> ctx,
                              com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        ServerPlayer caller = ctx.getSource().getPlayer();
        if (caller != null) {
            for (ServerPlayer p : caller.level().getServer().getPlayerList().getPlayers()) {
                if (p instanceof NumenPlayer np && np.isOwnedByPlayer(caller.getUUID())) {
                    builder.suggest(p.getName().getString());
                }
            }
        }
        return builder.buildFuture();
    }

    private static int sendToNamed(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "companion");
        String command = StringArgumentType.getString(ctx, "command");
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        NumenPlayer companion = ownedCompanion(owner, name);
        if (companion != null) {
            Services.NETWORK.sendToPlayer(owner,
                    new ClientGoalCommandPayload(companion.getUUID(), normalize(command)));
            ctx.getSource().sendSuccess(() ->
                    Component.literal("Sent goal command to '" + name + "'"), false);
            return 1;
        }
        String content = command.isBlank() ? name : name + " " + command;
        return sendToOwned(ctx, normalize(content));
    }

    private static int sendDirect(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "companion");
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        NumenPlayer companion = ownedCompanion(owner, name);
        if (companion != null) {
            Services.NETWORK.sendToPlayer(owner,
                    new ClientGoalCommandPayload(companion.getUUID(), "/goal status"));
            ctx.getSource().sendSuccess(() ->
                    Component.literal("Sent goal status to '" + name + "'"), false);
            return 1;
        }
        return sendToOwned(ctx, directCommand(name, false));
    }

    /** Decide the command for the ambiguous single-word server form. */
    static String directCommand(String candidate, boolean ownedCompanion) {
        String value = candidate == null ? "" : candidate.trim();
        if (ownedCompanion) return "/goal status";
        return normalize(value);
    }

    private static NumenPlayer ownedCompanion(ServerPlayer owner, String name) {
        if (name == null || name.isBlank()) return null;
        for (ServerPlayer p : owner.level().getServer().getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer np && np.isOwnedByPlayer(owner.getUUID())
                    && np.getName().getString().equalsIgnoreCase(name)) {
                return np;
            }
        }
        return null;
    }

    private static int sendToOwned(CommandContext<CommandSourceStack> ctx, String command)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        int sent = 0;
        for (ServerPlayer p : owner.level().getServer().getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer np && np.isOwnedByPlayer(owner.getUUID())) {
                Services.NETWORK.sendToPlayer(owner,
                        new ClientGoalCommandPayload(np.getUUID(), normalize(command)));
                sent++;
            }
        }
        if (sent == 0) {
            ctx.getSource().sendFailure(Component.literal("没有可发送 goal 指令的同伴"));
            return 0;
        }
        final int count = sent;
        ctx.getSource().sendSuccess(() ->
                Component.literal("Sent goal command to " + count + " companion(s)"), false);
        return count;
    }

    private static String normalize(String command) {
        String c = command == null ? "" : command.trim();
        if (c.isEmpty()) return "/goal";
        return c.startsWith("/") ? c : "/goal " + c;
    }
}
