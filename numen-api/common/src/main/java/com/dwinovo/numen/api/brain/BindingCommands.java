package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryHub;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

/**
 * {@code /numen companion <她> profile|persona|voice|skin [<条目>]}:给她换一项绑定,面板编辑卡的另一种按法,给没装客户端的
 * 主人用(装了的也能用)。条目写 {@code 命名空间:名字},补全列他看得到的"内置 + 我的"里没被他关掉的;不写条目是不绑
 * (档案不能不绑)。办的是 {@link CompanionBinding},和面板同一个入口。
 *
 * <p>召唤只要一个名字,档案、人设、声线、皮肤都是召唤之后用这条命令(或编辑卡)绑上的。没绑档案的她停在
 * {@code BLOCKED},主人跟她说话时她回"还没配模型档案"。
 */
public final class BindingCommands {

    private static final String COMPANION = "companion";
    private static final String ENTRY = "entry";

    private BindingCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        var her = CompanionArgument.of(COMPANION);
        for (Slot slot : Slot.values()) {
            var literal = Commands.literal(slot.command());
            if (slot != Slot.PROVIDER) {
                literal.executes(ctx -> bind(ctx, slot, null));
            }
            her.then(literal.then(Commands.argument(ENTRY, StringArgumentType.greedyString())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(entries(ctx.getSource(), slot), builder))
                    .executes(ctx -> bind(ctx, slot, StringArgumentType.getString(ctx, ENTRY).strip()))));
        }
        return Commands.literal(COMPANION).then(her);
    }

    /** 他此刻能绑的:看得到、没被他关掉的条目的名字。 */
    private static java.util.stream.Stream<String> entries(CommandSourceStack source, Slot slot) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return java.util.stream.Stream.empty();
        }
        return LibraryHub.of(player.server).library(slot.kind(), player.getUUID()).active().stream()
                .map(Library.Entry::id).map(Object::toString);
    }

    private static int bind(CommandContext<CommandSourceStack> ctx, Slot slot, String entry)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        Map.Entry<UUID, CompanionRegistry.Entry> her = CompanionArgument.resolve(ctx, COMPANION, owner);
        if (her == null) {
            return 0;
        }
        OwnerInputs.Applied applied = OwnerInputs.handle(owner.server, owner.getUUID(),
                new OwnerInput.Bind(her.getKey(), slot, entry), Venue.PANEL);
        if (applied.problem() != null) {
            ctx.getSource().sendFailure(Component.translatable(Keys.COMMAND_BIND_FAILED, applied.problem()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> entry == null
                ? Component.translatable(Keys.COMMAND_UNBOUND, her.getValue().name(), slot.command())
                : Component.translatable(Keys.COMMAND_BOUND, her.getValue().name(), slot.command(), entry), false);
        return 1;
    }
}
