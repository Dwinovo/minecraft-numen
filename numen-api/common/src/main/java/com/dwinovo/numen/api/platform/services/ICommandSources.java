package com.dwinovo.numen.api.platform.services;

import com.dwinovo.numen.api.mc.Echo;
import net.minecraft.commands.CommandSource;

/**
 * 原版的 {@link CommandSource} 在有的平台上多出抽象方法(Paper 加了返回 Bukkit 类型的 {@code getBukkitSender}),
 * common 的源码写不出那样的实现,所以"把一份回话的去处造成原版的来源"由平台来做。
 */
public interface ICommandSources {

    /** 指令说的话都收进 {@code echo} 的来源({@link com.dwinovo.numen.api.mc.EchoSource} 的一个子类)。 */
    CommandSource echoing(Echo echo);
}
