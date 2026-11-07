package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

/**
 * 每条用例不论成败都收场,下一条用例从已知的状态开始。
 *
 * <p>原版只在成功时清场地里的非玩家实体,失败什么都不清;她是 {@code ServerPlayer},原版从来不清。没收场的同伴还在走、在挖、占着
 * 程序的全服名额,失败就会连锁到后面的用例。这里照 NeoForge 测试框架的做法(它的测试玩家在通过与失败时都断开):
 * <ul>
 *   <li>用例登记的复原({@link #whenFinished}:她以外的玩家、改过的全服开关……)按登记的倒序执行;</li>
 *   <li>场地里(与原版成功时清场同一个范围)还在的同伴离开世界,她的程序与任务随身体离开收掉。</li>
 * </ul>
 * 收场挂在每条用例收场时的汇报上({@link TestTicks}),通过、失败、超时都走那一处。
 */
public final class TestCleanup {

    /** 每条在跑的用例的助手对应的用例名:登记复原时据此找到是哪一条。 */
    private static final Map<GameTestHelper, String> NAMES = Collections.synchronizedMap(new IdentityHashMap<>());
    /** 每条用例登记的复原。 */
    private static final Map<String, List<Runnable>> RESTORES = new ConcurrentHashMap<>();

    private TestCleanup() {}

    /** 用例开始:记下这个助手是哪一条({@link GameTestSuites} 在函数体前调)。 */
    static void started(GameTestHelper helper, String test) {
        NAMES.put(helper, test);
    }

    /** 这条用例收场时(通过、失败、超时都算)执行 {@code restore}:用例改了全服的东西、造了同伴以外的玩家,就在改的地方登记复原。 */
    public static void whenFinished(GameTestHelper helper, Runnable restore) {
        String test = NAMES.get(helper);
        if (test == null) {
            throw new IllegalStateException("这个助手不是经 GameTestSuites 开始的用例");
        }
        RESTORES.computeIfAbsent(test, name -> Collections.synchronizedList(new ArrayList<>())).add(restore);
    }

    /** 用例收场:执行它登记的复原,再让还在场地里的同伴离开世界。 */
    static void finished(GameTestInfo info) {
        NAMES.values().remove(NumenTestInstance.name(info));
        List<Runnable> restores = RESTORES.remove(NumenTestInstance.name(info));
        if (restores != null) {
            for (int i = restores.size() - 1; i >= 0; i--) {
                restores.get(i).run();
            }
        }
        AABB plot = info.getStructureBounds().inflate(1.0);
        for (ServerPlayer player : List.copyOf(info.getLevel().getServer().getPlayerList().getPlayers())) {
            if (player instanceof NumenPlayer companion && player.level() == info.getLevel()
                    && plot.contains(player.position())) {
                CompanionFactory.despawn(info.getLevel().getServer(), companion);
            }
        }
    }
}
