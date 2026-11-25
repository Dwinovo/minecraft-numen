package com.dwinovo.numen.api.brain;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /numen companion <她> ...} 这一支的根:挂在同伴参数下的是她身上服务端管的东西——皮肤({@link SkinCommand})。档案、人设、声线是
 * 她的大脑的配置,在主人的客户端上换。
 */
public final class BindingCommands {

    private static final String COMPANION = "companion";

    private BindingCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal(COMPANION).then(CompanionArgument.of(COMPANION).then(SkinCommand.node(COMPANION)));
    }
}
