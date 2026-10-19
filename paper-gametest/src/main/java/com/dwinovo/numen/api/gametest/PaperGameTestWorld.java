package com.dwinovo.numen.api.gametest;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.scheduler.CraftScheduler;
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

    /**
     * Paper 的接纳先把异步任务排进调度器,要等调度器的心跳才交给线程池,心跳是每刻开头做的一件事;用例在一刻的中间等,
     * 所以这里自己推心跳(此刻调度器没有在跑任务,不会重入),再把排给主线程的任务做完(答复正是其中之一)。
     * 做完排着的任务是 {@code managedBlock} 的事:不在阻塞态里,主线程只做"这一刻该做"的任务。
     */
    @Override
    public void await(MinecraftServer server, BooleanSupplier done) {
        CraftScheduler scheduler = (CraftScheduler) Bukkit.getScheduler();
        while (!done.getAsBoolean()) {
            scheduler.mainThreadHeartbeat(MinecraftServer.currentTick);
            // 阻塞态里主线程才肯做排给"以后几刻"的任务(答复就是这样排的),所以一次进、做完排着的任务就出来
            boolean[] entered = {false};
            server.managedBlock(() -> {
                boolean was = entered[0];
                entered[0] = true;
                return was;
            });
        }
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
