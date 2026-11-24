package com.dwinovo.numen.api.mc;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;

/**
 * {@link Echo} 在原版眼里的样子:指令说的每一句都收进它,成功的、失败的都收,知不知会管理员照它的来。
 */
final class EchoSource implements CommandSource {

    private final Echo echo;

    EchoSource(Echo echo) {
        this.echo = echo;
    }

    @Override
    public void sendSystemMessage(Component message) {
        echo.said(message);
    }

    @Override
    public boolean acceptsSuccess() {
        return true;
    }

    @Override
    public boolean acceptsFailure() {
        return true;
    }

    @Override
    public boolean shouldInformAdmins() {
        return echo.informAdmins();
    }
}
