package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 每一批从同一种状态开始,不看前面几批跑了多久。
 *
 * <p>前面几批只要有一条用例多跑或少跑几刻(原版烟花的寿命、MCP 的 HTTP),这一批开始的那一刻、开始时造过多少实体就跟着变。
 * 有两样东西按这两个数的余数做事:
 * <ul>
 *   <li>全服按刻数取余的周期:到点的表每 20 刻扫一次({@code TimerRegistry}),没装客户端的主人的动作栏每 20 刻一行
 *       ({@code CompanionLoops}),规则面板的推送每 10 刻一包({@code PermissionFeed});</li>
 *   <li>原版按实体编号错开的节拍:掉落物在地上每 {@code (tickCount + id) % 4} 刻才动一次,怪的部分 AI 也按编号错开。</li>
 * </ul>
 * 还有全服共用的世界随机数(掉落物的初速、战利品表):原版是不定种子的,前面几批用掉多少也跟着变。
 *
 * <p>所以每一批都等到服务器刻数是 {@value #PERIOD} 的整数倍的那一刻开头才开始(上面几种周期的公倍数),开始前把实体编号推到
 * {@value #ID_STRIDE} 的整数倍、把每个维度的世界随机数按世界种子与维度重新定种子:这一批里造的实体编号对这些余数而言、
 * 这一批掷出的点数,都与前面无关;每个实体自带的随机数(烟花的寿命)也按它在这一批里的编号定种子({@link #entitySeed})。模组加载器经 {@code GameTestBatchStartMixin} 接管
 * 原版的开批({@code GameTestRunner.runBatch})。
 */
public final class BatchStart {

    /** 每一批开始的刻数是它的整数倍。 */
    static final int PERIOD = 20;
    /** 每一批开始前实体编号推到它的整数倍:远大于一批里会造的实体数。 */
    static final int ID_STRIDE = 1 << 20;

    private static Runnable pending;
    private static boolean starting;

    private BatchStart() {}

    static void install() {
        CompanionTickDispatcher.beforeTick(server -> {
            if (pending != null && server.getTickCount() % PERIOD == 0) {
                Runnable start = pending;
                pending = null;
                alignEntityIds();
                for (ServerLevel level : server.getAllLevels()) {
                    level.getRandom().setSeed(level.getSeed() ^ level.dimension().identifier().toString().hashCode());
                }
                starting = true;
                try {
                    start.run();
                } finally {
                    starting = false;
                }
            }
        });
    }

    /**
     * 编号为 {@code id} 的实体的随机数种子(经 {@code EntityRandomMixin}):只看它在这一批里的编号(对 {@value #ID_STRIDE} 的余数),
     * 每一批开始时编号对齐,种子也就跟着从头排,与前面几批造过多少实体无关。
     */
    public static long entitySeed(int id) {
        return 0x9E3779B97F4A7C15L * ((id & (ID_STRIDE - 1)) + 1);
    }

    /** 原版要开一批了:正在按时开的放行({@code true}),否则记下来等下一个整数倍的刻开头再开。 */
    public static boolean admit(Runnable start) {
        if (starting) {
            return true;
        }
        pending = start;
        return false;
    }

    private static void alignEntityIds() {
        try {
            Field field = Entity.class.getDeclaredField("ENTITY_COUNTER");
            field.setAccessible(true);
            AtomicInteger counter = (AtomicInteger) field.get(null);
            counter.updateAndGet(id -> (id / ID_STRIDE + 1) * ID_STRIDE);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读不到原版的实体编号计数", e);
        }
    }
}
