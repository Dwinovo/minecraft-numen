package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.skin.SkinError;
import com.dwinovo.numen.api.skin.SkinFolder;
import com.dwinovo.numen.api.skin.SkinSigning;
import com.dwinovo.numen.api.skin.Wardrobe;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@code /numen companion <她> skin} 这一支(挂在 {@link BindingCommands} 的同伴参数下),给没装客户端的玩家直接给她设皮肤:
 * <ul>
 *   <li>{@code skin player <正版名>}:借这个正版玩家的皮肤;</li>
 *   <li>{@code skin url <网址>}:服务端去取这个网址的图;</li>
 *   <li>{@code skin server <文件名>}:服务器皮肤文件夹里的一张;补全列文件夹里的文件名;</li>
 *   <li>{@code skin}:脱掉,回到默认皮肤。</li>
 * </ul>
 * 都走 {@link Wardrobe} 那一条流程(拿到原图、查缓存、没签过就签、套上),和面板同一个入口。签过的当场穿上;没签过的排上服务端的队,
 * 说排第几,被限流在等了说一声,穿上了或没成都回一句。手臂模型看图自己判断。
 */
final class SkinCommand {

    private static final String ARG = "value";

    private SkinCommand() {}

    /** @param companionArgument 同伴那个参数的名字({@link CompanionArgument#resolve}) */
    static LiteralArgumentBuilder<CommandSourceStack> node(String companionArgument) {
        return Commands.literal("skin")
                .executes(ctx -> takeOff(ctx, companionArgument))
                .then(Commands.literal("player").then(Commands.argument(ARG, StringArgumentType.word())
                        .executes(ctx -> wear(ctx, companionArgument, SkinSigning.Source.Player::new))))
                .then(Commands.literal("url").then(Commands.argument(ARG, StringArgumentType.greedyString())
                        .executes(ctx -> wear(ctx, companionArgument, SkinSigning.Source.Url::new))))
                .then(Commands.literal("server").then(Commands.argument(ARG, StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(files(ctx.getSource()), builder))
                        .executes(ctx -> wear(ctx, companionArgument, null))));
    }

    /** 服务器皮肤文件夹里的文件名。 */
    private static java.util.stream.Stream<String> files(CommandSourceStack source) {
        return ServerSkins.of(source.getServer()).wardrobe().folder().list().stream().map(SkinFolder.Entry::name);
    }

    private static int takeOff(CommandContext<CommandSourceStack> ctx, String companionArgument) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        Map.Entry<UUID, CompanionRegistry.Entry> her = CompanionArgument.resolve(ctx, companionArgument, owner);
        if (her == null) {
            return 0;
        }
        ServerSkins.of(owner.server).wardrobe().takeOff(her.getKey());
        ctx.getSource().sendSuccess(() -> Component.translatable(Keys.COMMAND_SKIN_OFF, her.getValue().name()), false);
        return 1;
    }

    /** {@code origin} 把命令里的参数变成图的来源;是 {@code null} 就是服务器文件夹里叫这个名字的那张。 */
    private static int wear(CommandContext<CommandSourceStack> ctx, String companionArgument,
                            Function<String, SkinSigning.Source> origin) throws CommandSyntaxException {
        String argument = StringArgumentType.getString(ctx, ARG).strip();
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        Map.Entry<UUID, CompanionRegistry.Entry> her = CompanionArgument.resolve(ctx, companionArgument, owner);
        if (her == null) {
            return 0;
        }
        MinecraftServer server = owner.server;
        Wardrobe wardrobe = ServerSkins.of(server).wardrobe();
        UUID id = owner.getUUID();
        String herName = her.getValue().name();
        SkinSigning.Listener progress = new SkinSigning.Listener() {
            @Override
            public void queued(int position) {
                tell(server, id, Component.translatable(Keys.COMMAND_SKIN_QUEUED, argument, position));
            }

            @Override
            public void waiting(int seconds) {
                tell(server, id, Component.translatable(Keys.COMMAND_SKIN_WAITING, argument, seconds));
            }

            @Override
            public void done(SkinSigning.Outcome outcome) {
                tell(server, id, outcome.ok() ? Component.translatable(Keys.COMMAND_SKIN_DONE, argument, herName)
                        : Component.translatable(Keys.COMMAND_SKIN_FAILED, argument, outcome.problem().message()));
            }
        };
        long ticket;
        try {
            ticket = origin == null ? wardrobe.wearFile(id, her.getKey(), argument, null, progress)
                    : wardrobe.wear(id, her.getKey(), argument, origin.apply(argument), null, progress);
        } catch (SkinError refused) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_SKIN_FAILED, argument, refused.message()));
            return 0;
        }
        if (ticket < 0) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_SKIN_FULL, SkinSigning.MAX_PENDING_PER_OWNER));
            return 0;
        }
        return 1;
    }

    private static void tell(MinecraftServer server, UUID owner, Component message) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }
}
