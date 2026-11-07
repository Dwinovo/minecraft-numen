package com.dwinovo.numen.api.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 谁盯上了她的看护——纯 JVM,照 {@link CompanionHurtWatch} 的路子无头单测,MC 侧的读取(半径内谁的攻击目标是她)由 {@link Vigil} 喂进来。
 *
 * <p>一只怪的攻击目标变成她,发一次;它一直盯着就不再发。放弃又盯上的抖动,和她挨打共用一把窗口({@link CompanionHurtWatch#QUIET_TICKS}):
 * 窗口里再盯上的不发,窗口过后它若还盯着,也不补发——那次盯上已经发过了。
 */
final class TargetingWatch<K> {

    private final QuietWindows<K> quiet = new QuietWindows<>(CompanionHurtWatch.QUIET_TICKS);
    private Set<K> targeting = Set.of();

    /**
     * 每服务端 tick 喂一次。
     *
     * @param targetingNow 这一刻盯着她的(半径内)
     * @param now          当前游戏刻
     * @return 这一刻新盯上、该报的,按给的顺序
     */
    List<K> poll(Collection<K> targetingNow, long now) {
        List<K> fresh = new ArrayList<>();
        for (K key : targetingNow) {
            if (!targeting.contains(key) && quiet.admit(key, now)) {
                fresh.add(key);
            }
        }
        targeting = new HashSet<>(targetingNow);
        return fresh;
    }
}
