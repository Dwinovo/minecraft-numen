package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.mc.Echo;
import com.dwinovo.numen.api.mc.EchoSource;
import com.dwinovo.numen.api.platform.services.ICommandSources;
import net.minecraft.commands.CommandSource;

/** 原版的 CommandSource 在这个加载器上没有额外的抽象方法,收回话的来源原样就是 {@link EchoSource}。 */
public final class NeoForgeCommandSources implements ICommandSources {

    @Override
    public CommandSource echoing(Echo echo) {
        return new EchoSource(echo) {};
    }
}
