package com.dwinovo.numen.agent.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 缓存重计费:本该命中却重新处理的量。它是诊断,平时该接近零。 */
class CacheWasteTest {

    private static Usage turn(long input, long cacheRead) {
        return new Usage(input, 100, cacheRead, 0);
    }

    @Test
    void firstTurnHasNothingToCompareAgainst() {
        CacheWaste w = new CacheWaste();
        w.observe(turn(10_000, 0));
        assertEquals(0, w.missedTokens());
        assertEquals(0, w.missCount());
    }

    @Test
    void aFullHitCostsNothing() {
        CacheWaste w = new CacheWaste();
        w.observe(turn(10_000, 0));          // 建立缓存
        w.observe(turn(500, 10_000));        // 上轮那 10k 整段读回
        assertEquals(0, w.missedTokens());
    }

    @Test
    void aBustedPrefixIsCounted() {
        CacheWaste w = new CacheWaste();
        w.observe(new Usage(10_000, 100, 0, 5_000));   // 报了缓存活动
        w.observe(turn(20_000, 0));                    // 一点都没读回来
        // min(上轮 15000, 本轮 20000) - 0 = 15000
        assertEquals(15_000, w.missedTokens());
        assertEquals(1, w.missCount());
    }

    @Test
    void smallShortfallsAreNoiseNotMisses() {
        CacheWaste w = new CacheWaste();
        w.observe(new Usage(10_000, 100, 0, 100));
        w.observe(turn(200, 9_500));   // 差 600,低于噪声地板
        assertEquals(0, w.missedTokens());
    }

    @Test
    void providersThatNeverReportCacheAreNotAccused() {
        // 从不报缓存的服务商每轮 cacheRead 都是 0——不能把它算成"每轮全漏"
        CacheWaste w = new CacheWaste();
        w.observe(turn(10_000, 0));
        w.observe(turn(20_000, 0));
        w.observe(turn(30_000, 0));
        assertEquals(0, w.missedTokens());
    }

    @Test
    void compactionResetsTheComparison() {
        CacheWaste w = new CacheWaste();
        w.observe(new Usage(10_000, 100, 0, 5_000));
        w.reset();                       // 历史被剪断,前缀本来就换了
        w.observe(turn(20_000, 0));
        assertEquals(0, w.missedTokens());
    }

    @Test
    void missesAccumulateAcrossTurns() {
        CacheWaste w = new CacheWaste();
        w.observe(new Usage(10_000, 100, 0, 2_000));
        w.observe(turn(12_000, 0));      // 漏 12000
        w.observe(turn(12_000, 0));      // 再漏 12000
        assertEquals(24_000, w.missedTokens());
        assertEquals(2, w.missCount());
    }

    @Test
    void nullUsageIsIgnored() {
        CacheWaste w = new CacheWaste();
        w.observe(null);
        assertEquals(0, w.missCount());
    }
}
