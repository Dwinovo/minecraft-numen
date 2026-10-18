package com.dwinovo.numen.api.gametest;

import java.util.function.Consumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import org.bukkit.command.CommandSender;

/**
 * Paper 的做法:难度和游戏规则按世界给;{@link CommandSource} 要答"这条指令在 Bukkit 眼里是谁发的"
 * (有实体就是实体,没有就是控制台,与 Numen API 的 {@code PaperCommandSources} 同一个答法)。
 */
public final class PaperGameTestWorld implements GameTestWorld {

    @Override
    public void setDifficulty(ServerLevel level, Difficulty difficulty) {
        level.getServer().setDifficulty(level, difficulty, true);
    }

    @Override
    public void setRule(ServerLevel level, GameRules.BooleanValue rule, boolean value) {
        rule.set(value, level);
    }

    @Override
    public CommandSource hearing(Consumer<Component> heard) {
        return new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                heard.accept(message);
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
                return false;
            }

            @Override
            public CommandSender getBukkitSender(CommandSourceStack stack) {
                return stack.getEntity() != null ? stack.getEntity().getBukkitSender(stack) : stack.getServer().console;
            }
        };
    }
}
