package com.dwinovo.numen.api.gametest;

import java.util.ServiceLoader;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;

/**
 * 用例布置世界、扮发令人要用的几件事。原版与 Paper 里这几个方法的签名不一样(Paper 的难度与游戏规则按世界给,
 * {@link CommandSource} 还多一个抽象方法),夹具与用例写一份,具体做法由各平台的实现给出({@link ServiceLoader},
 * {@code META-INF/services/com.dwinovo.numen.api.gametest.GameTestWorld}):模组加载器用 {@code vanilla} 包里那份,
 * Paper 在自己的测试插件里。
 */
public interface GameTestWorld {

    GameTestWorld INSTANCE = ServiceLoader.load(GameTestWorld.class, GameTestWorld.class.getClassLoader())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("没有 GameTestWorld 的实现:平台的测试源码要提供一个"));

    /** 把 {@code level} 的难度定成 {@code difficulty},不管难度有没有被锁。 */
    void setDifficulty(ServerLevel level, Difficulty difficulty);

    /** 把 {@code level} 的一条开关式游戏规则定成 {@code value}。 */
    void setRule(ServerLevel level, GameRules.BooleanValue rule, boolean value);

    /**
     * 在主线程上等 {@code done} 成立,等的时候排给主线程的任务照做。她进世界的接纳在异步的平台上(Paper 的登录事件)要晚一点才有答复,
     * 答复也是排给主线程的任务,所以用例不能睡等、要在这里推着做;模组上答复当场就有,这里一步不用等。
     */
    void await(MinecraftServer server, BooleanSupplier done);

    /** 一个收下指令说的每一句话的发令来源:成功的、失败的都收,不知会管理员。 */
    CommandSource hearing(Consumer<Component> heard);
}
