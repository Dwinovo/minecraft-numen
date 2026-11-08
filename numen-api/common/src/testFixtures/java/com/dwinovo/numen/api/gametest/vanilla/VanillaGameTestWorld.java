package com.dwinovo.numen.api.gametest.vanilla;

import com.dwinovo.numen.api.gametest.GameTestWorld;
import com.dwinovo.numen.api.text.ServerLang;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;

/** 原版的做法(Forge、Fabric 的 GameTest 用)。 */
public final class VanillaGameTestWorld implements GameTestWorld {

    @Override
    public void setDifficulty(ServerLevel level, Difficulty difficulty) {
        level.getServer().setDifficulty(difficulty, true);
    }

    @Override
    public void setRule(ServerLevel level, GameRules.BooleanValue rule, boolean value) {
        rule.set(value, level.getServer());
    }

    @Override
    public String text(Component message) {
        return ServerLang.of("en_us").translate(message).getString();
    }

    @Override
    public void await(MinecraftServer server, BooleanSupplier done) {
        server.managedBlock(done);
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
        };
    }
}
