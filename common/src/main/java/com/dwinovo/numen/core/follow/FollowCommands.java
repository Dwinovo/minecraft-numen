package com.dwinovo.numen.core.follow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import com.dwinovo.numen.entity.NumenPlayer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric-registered owner-only subtree merged into the existing {@code /numen}
 * root.
 */
public final class FollowCommands {

    static final String COMPANION_ARGUMENT = "companion_name";
    static final String ACTION_ARGUMENT = "action";

    private FollowCommands() {}

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            FollowConfig config) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(config, "config");
        dispatcher.register(Commands.literal("numen")
                .then(Commands.literal("follow")
                        .then(Commands.argument(
                                COMPANION_ARGUMENT,
                                        StringArgumentType.word())
                                .suggests(FollowCommands::suggestOwnedCompanions)
                                .then(Commands.argument(
                                                ACTION_ARGUMENT,
                                                StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            for (FollowAction action
                                                    : FollowAction.values()) {
                                                builder.suggest(
                                                        action.argumentValue());
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(context ->
                                                executeParsed(context, config))))));
    }

    private static int executeParsed(
            CommandContext<CommandSourceStack> context,
            FollowConfig config) throws CommandSyntaxException {
        String rawAction = StringArgumentType.getString(
                context, ACTION_ARGUMENT);
        FollowAction action = FollowAction.parse(rawAction).orElse(null);
        if (action == null) {
            context.getSource().sendFailure(Component.literal(
                    "未知 follow action；只能使用 on、off、pause、resume 或 status。"));
            return 0;
        }
        return execute(context, action, config);
    }

    private static int execute(
            CommandContext<CommandSourceStack> context,
            FollowAction action,
            FollowConfig config) throws CommandSyntaxException {
        ServerPlayer owner = context.getSource().getPlayerOrException();
        NumenPlayer companion = requireOwnedCompanion(context, owner);
        if (companion == null) {
            return 0;
        }
        FollowControlResult result = FollowService.apply(
                owner.level().getServer(), companion, action, config);
        Component feedback = Component.literal(
                result.message() + "\n" + result.status().compactText());
        if (!result.success()) {
            context.getSource().sendFailure(feedback);
            return 0;
        }
        context.getSource().sendSuccess(() -> feedback, false);
        return 1;
    }

    private static java.util.concurrent.CompletableFuture<
            com.mojang.brigadier.suggestion.Suggestions>
            suggestOwnedCompanions(
                    CommandContext<CommandSourceStack> context,
                    com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        ServerPlayer owner = context.getSource().getPlayer();
        if (owner != null) {
            owner.level().getServer().getPlayerList().getPlayers().stream()
                    .filter(NumenPlayer.class::isInstance)
                    .map(NumenPlayer.class::cast)
                    .filter(companion -> companion.getOwnerUuid() != null
                            && companion.getOwnerUuid().equals(owner.getUUID()))
                    .map(companion -> companion.getName().getString())
                    .distinct()
                    .forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    private static NumenPlayer requireOwnedCompanion(
            CommandContext<CommandSourceStack> context,
            ServerPlayer owner) {
        String name = StringArgumentType.getString(
                context, COMPANION_ARGUMENT);
        List<NumenPlayer> online = new ArrayList<>();
        for (ServerPlayer player
                : owner.level().getServer().getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer companion) {
                online.add(companion);
            }
        }
        Resolution<NumenPlayer> resolution = resolveOwnedByName(
                online,
                owner.getUUID(),
                name,
                companion -> companion.getName().getString(),
                NumenPlayer::getOwnerUuid);
        return switch (resolution.code()) {
            case FOUND -> resolution.value();
            case INVALID_NAME -> {
                context.getSource().sendFailure(Component.literal(
                        "必须明确填写同伴名称；不支持 all 或 UUID 文本。"));
                yield null;
            }
            case NOT_FOUND -> {
                context.getSource().sendFailure(Component.literal(
                        "没有属于你且在线、名称为 '" + name + "' 的同伴。"));
                yield null;
            }
            case AMBIGUOUS -> {
                context.getSource().sendFailure(Component.literal(
                        "名称 '" + name + "' 匹配多个你的在线同伴，请先使用唯一名称。"));
                yield null;
            }
        };
    }

    static <T> Resolution<T> resolveOwnedByName(
            List<T> online,
            UUID ownerUuid,
            String requestedName,
            Function<T, String> name,
            Function<T, UUID> owner) {
        Objects.requireNonNull(online, "online");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(owner, "owner");
        if (requestedName == null
                || requestedName.isBlank()
                || requestedName.equalsIgnoreCase("all")
                || isUuidText(requestedName)) {
            return new Resolution<>(ResolutionCode.INVALID_NAME, null);
        }

        T match = null;
        for (T candidate : online) {
            if (requestedName.equals(name.apply(candidate))
                    && ownerUuid.equals(owner.apply(candidate))) {
                if (match != null) {
                    return new Resolution<>(ResolutionCode.AMBIGUOUS, null);
                }
                match = candidate;
            }
        }
        return match == null
                ? new Resolution<>(ResolutionCode.NOT_FOUND, null)
                : new Resolution<>(ResolutionCode.FOUND, match);
    }

    private static boolean isUuidText(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    enum ResolutionCode {
        FOUND,
        NOT_FOUND,
        AMBIGUOUS,
        INVALID_NAME
    }

    record Resolution<T>(ResolutionCode code, T value) {
        Resolution {
            Objects.requireNonNull(code, "code");
        }
    }
}
