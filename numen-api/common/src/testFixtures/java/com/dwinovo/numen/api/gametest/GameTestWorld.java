package com.dwinovo.numen.api.gametest;

import java.util.function.Consumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;

/** 用例布置世界、扮发令人要用的几件事。 */
public final class GameTestWorld {

    private GameTestWorld() {}

    /** 把 {@code level} 的难度定成 {@code difficulty},不管难度有没有被锁。 */
    public static void setDifficulty(ServerLevel level, Difficulty difficulty) {
        level.getServer().setDifficulty(difficulty, true);
    }

    /** 把 {@code level} 的一条开关式游戏规则定成 {@code value}。 */
    public static void setRule(ServerLevel level, GameRules.BooleanValue rule, boolean value) {
        rule.set(value, level.getServer());
    }

    /** 一个收下指令说的每一句话的发令来源:成功的、失败的都收,不知会管理员。 */
    public static CommandSource hearing(Consumer<Component> heard) {
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
