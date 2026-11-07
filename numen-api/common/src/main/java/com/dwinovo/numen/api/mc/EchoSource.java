package com.dwinovo.numen.api.mc;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;

/**
 * {@link Echo} 在原版眼里的样子:指令说的每一句都收进它,成功的、失败的都收,知不知会管理员照它的来。
 *
 * <p>抽象的,因为 {@link CommandSource} 在有的平台上多出抽象方法(Paper 的 {@code getBukkitSender} 返回 Bukkit 的类型),
 * 这些方法 common 写不出来,由各平台的 {@code ICommandSources} 实现补上再造出来。
 */
public abstract class EchoSource implements CommandSource {

    private final Echo echo;

    protected EchoSource(Echo echo) {
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
