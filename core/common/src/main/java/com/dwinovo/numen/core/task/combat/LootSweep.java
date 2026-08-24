package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.task.base.DropTracker;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 打倒一个目标之后走过去把掉落物捡了。
 *
 * <h2>为什么要先等一会儿</h2>
 * 死亡与掉落物落地之间隔着几刻。立刻去看会看见空地,于是她转身找下一个目标,
 * 战利品留在原地——{@link #DROP_LOITER_TICKS} 就是等这几刻。
 *
 * <h2>捡不到的要认账</h2>
 * 掉进岩浆、卡在墙里、落在够不着的悬崖下,都会让寻路反复失败。连续失败两次就把那一件
 * 拉黑并计数,回执里报给模型——她说"捡完了"和"有三件够不着"是两回事。
 */
final class LootSweep {

    /** 战果落地要几刻,这期间原地等。 */
    private static final int DROP_LOITER_TICKS = 5;
    /** 以尸体为心,这个半径内的掉落物算这一次的战利品。 */
    private static final double LOOT_RADIUS = 8.0;
    /** 同一件东西连续够不到几次就放弃它。 */
    private static final int MAX_APPROACH_FAILURES = 2;

    private final NumenPlayer player;
    private final DropTracker drops = new DropTracker();
    private final Set<Integer> skipped = new HashSet<>();

    private BlockPos deathPosition;
    private long settleUntil;
    private int approachFailures;
    private int unreachableCount;

    LootSweep(NumenPlayer player) {
        this.player = player;
    }

    /** 战斗途中持续记住"这些掉落物在我打之前就在地上了",免得把别人的东西算成战利品。 */
    void rememberPreexisting(BlockPos around) {
        drops.rememberExisting(player.level(),
                new AABB(around).inflate(LOOT_RADIUS));
    }

    /** 目标倒下了,从这一刻起开始收。 */
    void begin(BlockPos where) {
        deathPosition = where;
        settleUntil = player.level().getGameTime() + DROP_LOITER_TICKS;
        drops.resetTracking();
        skipped.clear();
        approachFailures = 0;
    }

    /** 还在等掉落物落地。 */
    boolean settling() {
        return player.level().getGameTime() <= settleUntil;
    }

    /** 把这一刻新出现的掉落物纳入视野。 */
    void discover() {
        if (deathPosition != null) {
            drops.discover((ServerLevel) player.level(),
                    new AABB(deathPosition).inflate(LOOT_RADIUS));
        }
    }

    /** 还够得着、还没被拉黑的掉落物。 */
    List<ItemEntity> live() {
        return drops.live((ServerLevel) player.level(), skipped);
    }

    void prune() {
        drops.prune((ServerLevel) player.level());
    }

    /** 走向所有还剩的掉落物(哪个先到算哪个)。 */
    NavGoal goal() {
        List<NavGoal> goals = live().stream()
                .map(item -> NavGoal.near(item.blockPosition(), 1.0))
                .toList();
        return goals.isEmpty() ? NavGoal.exact(player.blockPosition()) : NavGoal.composite(goals);
    }

    /** 又一次没走到。连续够了次数就把最近那件拉黑,否则下一轮再试。 */
    void noteApproachFailure() {
        if (++approachFailures < MAX_APPROACH_FAILURES) {
            return;
        }
        approachFailures = 0;
        drops.nearest((ServerLevel) player.level(), player, skipped).ifPresent(item -> {
            if (skipped.add(item.getId())) {
                unreachableCount++;
            }
        });
    }

    /** 这一趟收完了,回到战斗。 */
    void finish() {
        drops.clear();
        deathPosition = null;
    }

    int unreachableCount() {
        return unreachableCount;
    }
}
