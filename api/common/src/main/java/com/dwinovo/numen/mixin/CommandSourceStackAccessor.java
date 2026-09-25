package com.dwinovo.numen.mixin;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读 {@code CommandSourceStack} 的回话去处。她执行一行指令时,执行入口把这次调用放在这个去处里(见
 * {@code cli.Echo}),{@code /numen} 的节点从这里取回;原版只给换({@code withSource}),不给读。
 */
@Mixin(CommandSourceStack.class)
public interface CommandSourceStackAccessor {

    @Accessor("source")
    CommandSource numen$source();
}
