package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.skin.SkinError;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.skin.SkinSigning;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * {@code /numen skin add <名字> player <正版名>} 与 {@code /numen skin add <名字> url <图片网址>}:往他自己的皮肤库里加一条。
 * 签名走服务端的队({@link SkinSigning},面板上传的图也在这一队里):排上队了说排第几,被限流在等了说一声,签好了或没成都
 * 回他一句。名字不是英文字母的要加引号。装了客户端的在设置的皮肤页里能看到同一张队。
 */
public final class SkinCommand {

    private static final String NAME = "name";

    private SkinCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("skin").then(Commands.literal("add").then(Commands.argument(NAME, StringArgumentType.string())
                .then(Commands.literal("player").then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> add(ctx, new SkinSigning.Source.Player(StringArgumentType.getString(ctx, "player"))))))
                .then(Commands.literal("url").then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> add(ctx, new SkinSigning.Source.Url(StringArgumentType.getString(ctx, "url").strip())))))));
    }

    private static int add(CommandContext<CommandSourceStack> ctx, SkinSigning.Source source)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        MinecraftServer server = owner.server;
        String name = StringArgumentType.getString(ctx, NAME).strip();
        if (!EntryId.segment(name)) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_SKIN_BAD_NAME));
            return 0;
        }
        UUID id = owner.getUUID();
        SkinSigning.Request request = new SkinSigning.Request(id, name, SkinProfile.VARIANT_CLASSIC, source,
                LibraryHub.of(server).library(SkinProfile.KIND, id).mine(), EntryId.MINE, null);
        long ticket;
        try {
            ticket = SkinSignings.of(server).submit(request, new SkinSigning.Listener() {
                @Override
                public void queued(int position) {
                    tell(server, id, Component.translatable(Keys.COMMAND_SKIN_QUEUED, name, position));
                }

                @Override
                public void waiting(int seconds) {
                    tell(server, id, Component.translatable(Keys.COMMAND_SKIN_WAITING, name, seconds));
                }

                @Override
                public void done(SkinSigning.Outcome outcome) {
                    tell(server, id, outcome.ok() ? Component.translatable(Keys.COMMAND_SKIN_DONE, name, outcome.id().toString())
                            : Component.translatable(Keys.COMMAND_SKIN_FAILED, name, outcome.problem().message()));
                }
            });
        } catch (SkinError refused) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_SKIN_FAILED, name, refused.message()));
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
