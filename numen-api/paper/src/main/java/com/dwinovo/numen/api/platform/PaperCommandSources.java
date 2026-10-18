package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.mc.Echo;
import com.dwinovo.numen.api.mc.EchoSource;
import com.dwinovo.numen.api.platform.services.ICommandSources;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.bukkit.command.CommandSender;

/**
 * Paper 的 {@code CommandSource} 要答"这条指令在 Bukkit 眼里是谁发的"(权限节点按这个人查)。
 * 来源有实体就是实体(她这具身体),没有(服务器自己的来源)就是控制台。
 */
public final class PaperCommandSources implements ICommandSources {

    @Override
    public CommandSource echoing(Echo echo) {
        return new EchoSource(echo) {
            @Override
            public CommandSender getBukkitSender(CommandSourceStack stack) {
                return stack.getEntity() != null ? stack.getEntity().getBukkitSender(stack)
                        : stack.getServer().console;
            }
        };
    }
}
